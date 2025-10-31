@file:Suppress("DEPRECATION")

package com.example.messenger_app.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ПОЛНОСТЬЮ РАБОЧИЙ WebRTC Call Manager
 *
 * Возможности:
 * - Аудио и видео звонки через WebRTC
 * - Автоматическое переподключение при потере связи
 * - Таймаут звонка (30 секунд)
 * - Переход с аудио на видео
 * - STUN/TURN серверы для NAT traversal
 * - Управление аудио (микрофон, спикер)
 * - Управление видео (камера, переключение)
 * - Индикаторы состояния и качества
 */
object WebRtcCallManager {

    private const val TAG = "WebRtcCallManager"
    private const val CALL_TIMEOUT_MS = 30_000L
    private const val RECONNECT_ATTEMPTS = 3
    private const val RECONNECT_DELAY_MS = 2_000L

    // ==================== PUBLIC STATE ====================

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn

    private val _isVideoEnabled = MutableStateFlow(false)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled

    private val _callStartedAtMs = MutableStateFlow<Long?>(null)
    val callStartedAtMs: StateFlow<Long?> = _callStartedAtMs

    enum class ConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }
    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    data class VideoUpgradeRequest(val fromUsername: String)
    private val _videoUpgradeRequest = MutableStateFlow<VideoUpgradeRequest?>(null)
    val videoUpgradeRequest: StateFlow<VideoUpgradeRequest?> = _videoUpgradeRequest

    enum class Quality { Good, Medium, Poor }
    private val _callQuality = MutableStateFlow(Quality.Good)
    val callQuality: StateFlow<Quality> = _callQuality

    // ==================== SIGNALING INTERFACE ====================

    interface SignalingDelegate {
        fun onLocalDescription(callId: String, sdp: SessionDescription)
        fun onIceCandidate(callId: String, candidate: IceCandidate)
        fun onCallTimeout(callId: String)
        fun onConnectionFailed(callId: String)
        fun onVideoUpgradeRequest()
    }

    @Volatile
    var signalingDelegate: SignalingDelegate? = null

    // ==================== INTERNALS ====================

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())

    // WebRTC components
    private var eglBase: EglBase? = null
    private var pcFactory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null

    // Audio
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Video
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null

    // State
    private var currentCallId: String? = null
    private var currentRole: String? = null
    private val isStarted = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    // Renderers
    private var localRendererRef: WeakReference<SurfaceViewRenderer>? = null
    private var remoteRendererRef: WeakReference<SurfaceViewRenderer>? = null
    private val initializedRenderers = Collections.newSetFromMap(WeakHashMap<SurfaceViewRenderer, Boolean>())

    // ICE candidates queue (для кандидатов до установки remote description)
    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    // ==================== INITIALIZATION ====================

    /**
     * Инициализация WebRTC
     * ВАЖНО: Вызвать перед использованием!
     */
    fun init(context: Context) {
        if (::appContext.isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }

        Log.d(TAG, "========== INITIALIZING WebRTC ==========")
        appContext = context.applicationContext

        // Load native library
        try {
            System.loadLibrary("jingle_peerconnection_so")
            Log.d(TAG, "Native library loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load native library", e)
        }

        // Initialize PeerConnectionFactory
        val options = PeerConnectionFactory.InitializationOptions
            .builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)
        Log.d(TAG, "PeerConnectionFactory initialized")

        // Create EGL context
        eglBase = EglBase.create()
        Log.d(TAG, "EglBase created")

        // Video encoders/decoders
        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        // Audio device module
        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .createAudioDeviceModule()

        // Create PeerConnectionFactory
        pcFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(adm)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        Log.d(TAG, "========== WebRTC INITIALIZED ==========")
    }

    // ==================== CALL MANAGEMENT ====================

    /**
     * Начать звонок
     *
     * @param callId Уникальный ID звонка
     * @param isVideo Видео звонок?
     * @param playRingback Играть звук набора? (только для caller)
     * @param role "caller" или "callee"
     */
    @Synchronized
    fun startCall(
        callId: String,
        isVideo: Boolean,
        playRingback: Boolean,
        role: String
    ) {
        require(::appContext.isInitialized) { "Call init(context) first!" }
        require(role == "caller" || role == "callee") { "Invalid role: $role" }

        Log.d(TAG, "========== START CALL ==========")
        Log.d(TAG, "callId: $callId")
        Log.d(TAG, "isVideo: $isVideo")
        Log.d(TAG, "playRingback: $playRingback")
        Log.d(TAG, "role: $role")
        Log.d(TAG, "================================")

        // Если уже запущен тот же звонок, игнорируем
        if (isStarted.get() && currentCallId == callId) {
            Log.w(TAG, "Call already running, ignoring")
            return
        }

        // Если запущен другой звонок, завершаем его
        if (isStarted.get() && currentCallId != callId) {
            Log.w(TAG, "Ending previous call: $currentCallId")
            endCallInternal(true)
        }

        // Инициализация состояния
        currentCallId = callId
        currentRole = role
        isStarted.set(true)
        _isVideoEnabled.value = isVideo
        _callStartedAtMs.value = null
        _callQuality.value = Quality.Good
        _connectionState.value = ConnectionState.CONNECTING
        reconnectAttempt = 0
        remoteDescriptionSet = false
        pendingIceCandidates.clear()

        // Настройка аудио
        setupAudioForCall(videoMode = isVideo)

        // Таймаут для caller
        if (role == "caller") {
            startCallTimeout(callId)
        }

        // TURN/STUN серверы
        val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:sil-video.ru:3478").createIceServer(),
            IceServer.builder("turn:sil-video.ru:3478?transport=udp")
                .setUsername("melvud")
                .setPassword("berkut14")
                .createIceServer(),
            IceServer.builder("turn:sil-video.ru:3478?transport=tcp")
                .setUsername("melvud")
                .setPassword("berkut14")
                .createIceServer(),
            IceServer.builder("turns:sil-video.ru:443?transport=tcp")
                .setUsername("melvud")
                .setPassword("berkut14")
                .createIceServer()
        )

        // RTCConfiguration
        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            enableDtlsSrtp = true
            // Более агрессивные настройки ICE
            iceConnectionReceivingTimeout = 2000
            iceBackupCandidatePairPingInterval = 1000
        }

        // Создание PeerConnection
        Log.d(TAG, "Creating PeerConnection...")
        peer = pcFactory!!.createPeerConnection(rtcConfig, pcObserver)

        if (peer == null) {
            Log.e(TAG, "FATAL: Failed to create PeerConnection")
            _connectionState.value = ConnectionState.FAILED
            endCallInternal(true)
            return
        }

        Log.d(TAG, "PeerConnection created successfully")

        // Настройка аудио трека
        setupAudioTrack()

        // Настройка видео трека (если нужно)
        if (isVideo) {
            createAndStartLocalVideo()
        }

        // Caller создает offer
        if (role == "caller") {
            Log.d(TAG, "Role=CALLER: creating offer...")
            createOffer()
        } else {
            Log.d(TAG, "Role=CALLEE: waiting for offer...")
        }

        Log.d(TAG, "========== START CALL COMPLETE ==========")
    }

    /**
     * Завершить звонок
     */
    fun endCall() {
        Log.d(TAG, "endCall() called")
        endCallInternal(true)
    }

    @Synchronized
    private fun endCallInternal(releaseAll: Boolean) {
        if (!isStarted.get() && !releaseAll) {
            Log.d(TAG, "Call not started, nothing to end")
            return
        }

        Log.d(TAG, "========== END CALL ==========")
        isStarted.set(false)
        _callStartedAtMs.value = null
        _connectionState.value = ConnectionState.DISCONNECTED

        cancelCallTimeout()
        cancelReconnect()

        // Close peer connection
        peer?.let {
            try {
                it.close()
                Log.d(TAG, "PeerConnection closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing peer", e)
            }
        }
        peer = null

        // Dispose video
        disposeVideoChain()

        // Dispose audio
        try {
            audioTrack?.dispose()
            audioSource?.dispose()
            Log.d(TAG, "Audio disposed")
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing audio", e)
        }
        audioTrack = null
        audioSource = null

        // Detach renderers
        detachRenderer(true)
        detachRenderer(false)
        remoteVideoTrack = null

        // Teardown audio
        teardownAudio()

        currentCallId = null
        currentRole = null
        remoteDescriptionSet = false
        pendingIceCandidates.clear()

        Log.d(TAG, "========== END CALL COMPLETE ==========")
    }

    // ==================== AUDIO SETUP ====================

    private fun setupAudioTrack() {
        Log.d(TAG, "Setting up audio track...")

        val audioConstraints = MediaConstraints().apply {
            // Обязательные
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            // Опциональные
            optional.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
        }

        audioSource = pcFactory!!.createAudioSource(audioConstraints)
        audioTrack = pcFactory!!.createAudioTrack("AUDIO_TRACK", audioSource)
        audioTrack?.setEnabled(true)

        // Добавляем в peer
        peer?.addTrack(audioTrack, listOf("STREAM"))

        Log.d(TAG, "Audio track created and added to peer")
    }

    private fun setupAudioForCall(videoMode: Boolean) {
        val am = audioManager ?: return
        Log.d(TAG, "Setting up audio for call (videoMode=$videoMode)")

        // Request audio focus
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val afr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                }
                .build()
            audioFocusRequest = afr
            am.requestAudioFocus(afr)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }

        // Set mode
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        // Определяем нужен ли спикер
        val shouldUseSpeaker = when {
            videoMode -> true
            deviceHasNoEarpiece(am) -> true
            isEmulator() -> true
            hasHeadsetOrBt(am) -> false
            else -> false
        }

        setSpeakerphone(am, shouldUseSpeaker)
        _isMuted.value = false

        Log.d(TAG, "Audio setup complete: speaker=$shouldUseSpeaker")
    }

    private fun teardownAudio() {
        val am = audioManager ?: return
        Log.d(TAG, "Tearing down audio")

        try {
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = false
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting audio", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                try {
                    am.abandonAudioFocusRequest(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error abandoning audio focus", e)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }

        audioFocusRequest = null
        Log.d(TAG, "Audio teardown complete")
    }

    // ==================== AUDIO CONTROLS ====================

    fun toggleMic() {
        val willMute = !_isMuted.value
        audioTrack?.setEnabled(!willMute)
        _isMuted.value = willMute
        Log.d(TAG, "Microphone ${if (willMute) "MUTED" else "UNMUTED"}")
    }

    fun toggleSpeaker() {
        val am = audioManager ?: return
        setSpeakerphone(am, !am.isSpeakerphoneOn)
    }

    private fun setSpeakerphone(am: AudioManager, on: Boolean) {
        try {
            am.isSpeakerphoneOn = on
            _isSpeakerOn.value = on
            Log.d(TAG, "Speakerphone ${if (on) "ON" else "OFF"}")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting speakerphone", e)
        }
    }

    private fun deviceHasNoEarpiece(am: AudioManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .none { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        } else {
            false
        }
    }

    private fun hasHeadsetOrBt(am: AudioManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type in listOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                )
            }
        } else {
            @Suppress("DEPRECATION")
            am.isWiredHeadsetOn || am.isBluetoothScoOn || am.isBluetoothA2dpOn
        }
    }

    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fp.contains("generic") || model.contains("emulator") || product.contains("sdk")
    }

    // ==================== VIDEO SETUP ====================

    private fun createAndStartLocalVideo() {
        if (videoTrack != null) {
            Log.d(TAG, "Video track already exists")
            return
        }

        Log.d(TAG, "Creating local video...")

        val capturer = createCameraCapturer() ?: run {
            Log.e(TAG, "Failed to create camera capturer")
            _isVideoEnabled.value = false
            return
        }

        videoCapturer = capturer

        surfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread",
            eglBase!!.eglBaseContext
        )

        videoSource = pcFactory!!.createVideoSource(capturer.isScreencast)
        capturer.initialize(
            surfaceTextureHelper,
            appContext,
            videoSource!!.capturerObserver
        )

        // Start capture
        capturer.startCapture(1280, 720, 30)
        Log.d(TAG, "Camera capture started: 1280x720@30fps")

        // Create video track
        videoTrack = pcFactory!!.createVideoTrack("VIDEO_TRACK", videoSource)
        videoTrack?.setEnabled(true)

        // Add to peer
        peer?.addTrack(videoTrack, listOf("STREAM"))

        _isVideoEnabled.value = true

        // Attach to local renderer if exists
        localRendererRef?.get()?.let { attachLocalSinkTo(it) }

        Log.d(TAG, "Local video created and added to peer")
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)

        // Try front camera first
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "Using front camera: $deviceName")
                return enumerator.createCapturer(deviceName, null)
            }
        }

        // Fallback to back camera
        for (deviceName in enumerator.deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "Using back camera: $deviceName")
                return enumerator.createCapturer(deviceName, null)
            }
        }

        Log.e(TAG, "No camera found!")
        return null
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                Log.d(TAG, "Camera switched: front=$isFrontFacing")
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.e(TAG, "Camera switch error: $errorDescription")
            }
        })
    }

    fun toggleVideo() {
        val willEnable = !_isVideoEnabled.value
        Log.d(TAG, "Toggle video: $willEnable")

        if (willEnable) {
            if (videoTrack == null) {
                createAndStartLocalVideo()
            } else {
                videoTrack?.setEnabled(true)
            }
            // Включаем спикер при видео
            audioManager?.let { setSpeakerphone(it, true) }
        } else {
            videoTrack?.setEnabled(false)
        }

        _isVideoEnabled.value = willEnable
    }

    private fun disposeVideoChain() {
        Log.d(TAG, "Disposing video chain...")

        try {
            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing capturer", e)
        }

        try {
            surfaceTextureHelper?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing texture helper", e)
        }

        try {
            videoTrack?.dispose()
            videoSource?.dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing video track/source", e)
        }

        videoCapturer = null
        surfaceTextureHelper = null
        videoTrack = null
        videoSource = null
        _isVideoEnabled.value = false

        Log.d(TAG, "Video chain disposed")
    }

    // ==================== VIDEO UPGRADE ====================

    fun requestVideoUpgrade() {
        if (_isVideoEnabled.value) {
            Log.d(TAG, "Video already enabled")
            return
        }

        Log.d(TAG, "Requesting video upgrade")
        signalingDelegate?.onVideoUpgradeRequest()
        toggleVideo()
    }

    fun acceptVideoUpgrade() {
        Log.d(TAG, "Accepting video upgrade")
        _videoUpgradeRequest.value = null
        if (!_isVideoEnabled.value) {
            toggleVideo()
        }
    }

    fun declineVideoUpgrade() {
        Log.d(TAG, "Declining video upgrade")
        _videoUpgradeRequest.value = null
    }

    fun onRemoteVideoUpgradeRequest(fromUsername: String) {
        Log.d(TAG, "Remote video upgrade request from: $fromUsername")
        _videoUpgradeRequest.value = VideoUpgradeRequest(fromUsername)
    }

    // ==================== RENDERERS ====================

    fun prepareRenderer(view: SurfaceViewRenderer, mirror: Boolean, overlay: Boolean) {
        val ctx = eglBase?.eglBaseContext ?: run {
            Log.e(TAG, "EglBase not initialized")
            return
        }

        val firstInit = !initializedRenderers.contains(view)

        if (firstInit) {
            view.setZOrderOnTop(false)
            view.setZOrderMediaOverlay(overlay)
            view.init(ctx, null)
            view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            view.setEnableHardwareScaler(true)
            initializedRenderers.add(view)
            Log.d(TAG, "Renderer initialized: overlay=$overlay")
        }

        view.setMirror(mirror)
    }

    fun bindLocalRenderer(view: SurfaceViewRenderer) {
        val prev = localRendererRef?.get()
        if (prev != null && prev !== view) {
            try {
                videoTrack?.removeSink(prev)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing old local sink", e)
            }
        }

        localRendererRef = WeakReference(view)
        attachLocalSinkTo(view)
        Log.d(TAG, "Local renderer bound")
    }

    fun bindRemoteRenderer(view: SurfaceViewRenderer) {
        val prev = remoteRendererRef?.get()
        if (prev != null && prev !== view) {
            try {
                remoteVideoTrack?.removeSink(prev)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing old remote sink", e)
            }
        }

        remoteRendererRef = WeakReference(view)
        attachRemoteSinkTo(view)
        Log.d(TAG, "Remote renderer bound")
    }

    private fun attachLocalSinkTo(view: SurfaceViewRenderer) {
        postWhenAttached(view) {
            try {
                videoTrack?.addSink(view)
                view.invalidate()
                Log.d(TAG, "Local sink attached")
            } catch (e: Exception) {
                Log.e(TAG, "Error attaching local sink", e)
            }
        }
    }

    private fun attachRemoteSinkTo(view: SurfaceViewRenderer) {
        postWhenAttached(view) {
            try {
                remoteVideoTrack?.addSink(view)
                view.invalidate()
                Log.d(TAG, "Remote sink attached")
            } catch (e: Exception) {
                Log.e(TAG, "Error attaching remote sink", e)
            }
        }
    }

    private fun detachRenderer(local: Boolean) {
        val ref = if (local) localRendererRef else remoteRendererRef
        val view = ref?.get()

        if (view != null) {
            try {
                if (local) {
                    videoTrack?.removeSink(view)
                } else {
                    remoteVideoTrack?.removeSink(view)
                }
                Log.d(TAG, "${if (local) "Local" else "Remote"} renderer detached")
            } catch (e: Exception) {
                Log.e(TAG, "Error detaching renderer", e)
            }
        }

        if (local) localRendererRef = null else remoteRendererRef = null
    }

    private fun postWhenAttached(view: SurfaceViewRenderer, block: () -> Unit) {
        val runBlock = {
            if (view.isAttachedToWindow) {
                block()
            } else {
                view.post {
                    if (view.isAttachedToWindow) block()
                }
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            runBlock()
        } else {
            mainHandler.post { runBlock() }
        }
    }

    // ==================== SDP / SIGNALING ====================

    private fun createOffer() {
        Log.d(TAG, "Creating offer...")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peer?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                val desc = sdp ?: run {
                    Log.e(TAG, "SDP is null in onCreateSuccess")
                    return
                }

                Log.d(TAG, "Offer created, setting local description")

                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set, sending to signaling")
                        signalingDelegate?.onLocalDescription(currentCallId ?: return, desc)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set local description: $error")
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create offer: $error")
            }
        }, constraints)
    }

    private fun createAnswer() {
        Log.d(TAG, "Creating answer...")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peer?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                val desc = sdp ?: run {
                    Log.e(TAG, "SDP is null in onCreateSuccess")
                    return
                }

                Log.d(TAG, "Answer created, setting local description")

                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set, sending to signaling")
                        signalingDelegate?.onLocalDescription(currentCallId ?: return, desc)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set local description: $error")
                    }
                }, desc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create answer: $error")
            }
        }, constraints)
    }

    fun applyRemoteOffer(sdp: String) {
        Log.d(TAG, "========== APPLY REMOTE OFFER ==========")
        Log.d(TAG, "SDP length: ${sdp.length}")
        Log.d(TAG, "Peer exists: ${peer != null}")
        Log.d(TAG, "Current role: $currentRole")

        val p = peer ?: run {
            Log.e(TAG, "FATAL: peer is null!")
            return
        }

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)

        p.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote offer set successfully")
                remoteDescriptionSet = true

                // Добавляем отложенные ICE candidates
                if (pendingIceCandidates.isNotEmpty()) {
                    Log.d(TAG, "Adding ${pendingIceCandidates.size} pending ICE candidates")
                    pendingIceCandidates.forEach { candidate ->
                        p.addIceCandidate(candidate)
                    }
                    pendingIceCandidates.clear()
                }

                // Создаем answer
                createAnswer()
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote offer: $error")
            }
        }, offer)

        Log.d(TAG, "=======================================")
    }

    fun applyRemoteAnswer(sdp: String) {
        Log.d(TAG, "========== APPLY REMOTE ANSWER ==========")
        Log.d(TAG, "SDP length: ${sdp.length}")
        Log.d(TAG, "Peer exists: ${peer != null}")
        Log.d(TAG, "Current role: $currentRole")

        val p = peer ?: run {
            Log.e(TAG, "FATAL: peer is null!")
            return
        }

        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)

        p.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote answer set successfully")
                remoteDescriptionSet = true

                // Добавляем отложенные ICE candidates
                if (pendingIceCandidates.isNotEmpty()) {
                    Log.d(TAG, "Adding ${pendingIceCandidates.size} pending ICE candidates")
                    pendingIceCandidates.forEach { candidate ->
                        p.addIceCandidate(candidate)
                    }
                    pendingIceCandidates.clear()
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote answer: $error")
            }
        }, answer)

        Log.d(TAG, "========================================")
    }

    fun addRemoteIceCandidate(mid: String, index: Int, cand: String) {
        val candidate = IceCandidate(mid, index, cand)

        if (!remoteDescriptionSet) {
            Log.d(TAG, "Remote description not set yet, queuing ICE candidate")
            pendingIceCandidates.add(candidate)
            return
        }

        val p = peer
        if (p == null) {
            Log.w(TAG, "Cannot add ICE candidate: peer is null")
            return
        }

        p.addIceCandidate(candidate)
        Log.d(TAG, "ICE candidate added: mid=$mid, index=$index")
    }

    // ==================== PEER CONNECTION OBSERVER ====================

    private val pcObserver = object : PeerConnection.Observer {

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling state: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE connection state: $state")

            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    mainHandler.post {
                        if (_callStartedAtMs.value == null) {
                            _callStartedAtMs.value = System.currentTimeMillis()
                        }
                        _connectionState.value = ConnectionState.CONNECTED
                        cancelCallTimeout()
                        cancelReconnect()
                        Log.d(TAG, "Call CONNECTED")
                    }
                }

                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    mainHandler.post {
                        if (_connectionState.value == ConnectionState.CONNECTED) {
                            Log.w(TAG, "Connection lost, attempting reconnect...")
                            attemptReconnect()
                        }
                    }
                }

                PeerConnection.IceConnectionState.FAILED -> {
                    mainHandler.post {
                        Log.e(TAG, "ICE connection FAILED")
                        _connectionState.value = ConnectionState.FAILED
                    }
                }

                else -> {}
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            Log.d(TAG, "Peer connection state: $newState")

            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    mainHandler.post {
                        _connectionState.value = ConnectionState.CONNECTED
                        cancelCallTimeout()
                        cancelReconnect()
                    }
                }

                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    mainHandler.post {
                        if (_connectionState.value == ConnectionState.CONNECTED) {
                            attemptReconnect()
                        }
                    }
                }

                PeerConnection.PeerConnectionState.FAILED -> {
                    mainHandler.post {
                        _connectionState.value = ConnectionState.FAILED
                    }
                }

                else -> {}
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "ICE receiving: $receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering state: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            val c = candidate ?: return
            val id = currentCallId ?: return

            Log.d(TAG, "New ICE candidate: ${c.sdpMid}:${c.sdpMLineIndex}")
            signalingDelegate?.onIceCandidate(id, c)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            Log.d(TAG, "ICE candidates removed: ${candidates?.size}")
        }

        override fun onAddStream(stream: org.webrtc.MediaStream?) {
            Log.d(TAG, "Stream added (legacy)")
            stream?.videoTracks?.firstOrNull()?.let { handleRemoteTrack(it) }
        }

        override fun onRemoveStream(stream: org.webrtc.MediaStream?) {
            Log.d(TAG, "Stream removed (legacy)")
        }

        override fun onDataChannel(channel: org.webrtc.DataChannel?) {
            Log.d(TAG, "Data channel: ${channel?.label()}")
        }

        override fun onRenegotiationNeeded() {
            Log.d(TAG, "Renegotiation needed")
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
            Log.d(TAG, "Track added: ${receiver?.track()?.kind()}")
            receiver?.track()?.let { handleRemoteTrack(it) }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            Log.d(TAG, "Track received: ${transceiver?.receiver?.track()?.kind()}")
            transceiver?.receiver?.track()?.let { handleRemoteTrack(it) }
        }
    }

    private fun handleRemoteTrack(track: MediaStreamTrack) {
        Log.d(TAG, "Handling remote track: ${track.kind()}")

        when (track) {
            is VideoTrack -> {
                mainHandler.post {
                    val view = remoteRendererRef?.get()
                    if (view != null) {
                        try {
                            remoteVideoTrack?.removeSink(view)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error removing old sink", e)
                        }
                    }

                    remoteVideoTrack = track
                    track.setEnabled(true)

                    if (view != null) {
                        attachRemoteSinkTo(view)
                    }

                    Log.d(TAG, "Remote VIDEO track attached")
                }
            }

            is AudioTrack -> {
                track.setEnabled(true)
                Log.d(TAG, "Remote AUDIO track enabled")
            }
        }
    }

    // ==================== RECONNECTION ====================

    private fun attemptReconnect() {
        if (reconnectAttempt >= RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached")
            mainHandler.post {
                _connectionState.value = ConnectionState.FAILED
                signalingDelegate?.onConnectionFailed(currentCallId ?: return@post)
                endCallInternal(true)
            }
            return
        }

        reconnectAttempt++
        mainHandler.post {
            _connectionState.value = ConnectionState.RECONNECTING
        }

        Log.d(TAG, "Reconnect attempt $reconnectAttempt/$RECONNECT_ATTEMPTS")

        // Restart ICE
        peer?.restartIce()

        // Schedule next attempt
        reconnectRunnable = Runnable {
            if (_connectionState.value == ConnectionState.RECONNECTING) {
                attemptReconnect()
            }
        }
        mainHandler.postDelayed(reconnectRunnable!!, RECONNECT_DELAY_MS)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
        reconnectAttempt = 0
    }

    // ==================== TIMEOUT ====================

    private fun startCallTimeout(callId: String) {
        cancelCallTimeout()

        timeoutRunnable = Runnable {
            if (currentCallId == callId && _connectionState.value != ConnectionState.CONNECTED) {
                Log.w(TAG, "Call timeout reached for: $callId")
                mainHandler.post {
                    _connectionState.value = ConnectionState.FAILED
                    signalingDelegate?.onCallTimeout(callId)
                    endCallInternal(true)
                }
            }
        }

        mainHandler.postDelayed(timeoutRunnable!!, CALL_TIMEOUT_MS)
        Log.d(TAG, "Call timeout started: ${CALL_TIMEOUT_MS}ms")
    }

    private fun cancelCallTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    // ==================== QUALITY MONITORING ====================

    fun updateQualityFromStats(bitrateKbps: Int, rttMs: Int, packetLossPct: Int) {
        val quality = when {
            packetLossPct >= 10 || rttMs >= 800 || bitrateKbps < 200 -> Quality.Poor
            packetLossPct >= 3 || rttMs >= 250 || bitrateKbps < 600 -> Quality.Medium
            else -> Quality.Good
        }

        if (_callQuality.value != quality) {
            _callQuality.value = quality
            Log.d(TAG, "Call quality: $quality (bitrate=$bitrateKbps, rtt=$rttMs, loss=$packetLossPct%)")
        }
    }

    // ==================== SDP OBSERVER ADAPTER ====================

    abstract class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "SDP create failure: $error")
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "SDP set failure: $error")
        }
    }
}