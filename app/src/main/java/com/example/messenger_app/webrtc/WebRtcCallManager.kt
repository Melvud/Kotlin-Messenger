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
 * УЛУЧШЕННЫЙ WebRTC менеджер с:
 * - Автоматическим переподключением при потере связи
 * - Таймаутом звонка в 30 секунд для исходящих
 * - Поддержкой перехода аудио -> видео с уведомлением
 */
object WebRtcCallManager {

    private const val TAG = "WebRtcCallManager"
    private const val CALL_TIMEOUT_MS = 30_000L // 30 секунд таймаут
    private const val RECONNECT_ATTEMPTS = 3 // попыток переподключения
    private const val RECONNECT_DELAY_MS = 2_000L // задержка между попытками

    // ---------- public UI state ----------
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn

    private val _isVideoEnabled = MutableStateFlow(false)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled

    private val _callStartedAtMs = MutableStateFlow<Long?>(null)
    val callStartedAtMs: StateFlow<Long?> = _callStartedAtMs

    // НОВОЕ: Индикатор состояния соединения
    enum class ConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }
    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    // НОВОЕ: Запрос на переход в видео режим
    data class VideoUpgradeRequest(val fromUsername: String)
    private val _videoUpgradeRequest = MutableStateFlow<VideoUpgradeRequest?>(null)
    val videoUpgradeRequest: StateFlow<VideoUpgradeRequest?> = _videoUpgradeRequest

    enum class Quality { Good, Medium, Poor }
    private val _callQuality = MutableStateFlow(Quality.Good)
    val callQuality: StateFlow<Quality> = _callQuality

    // ---------- signaling ----------
    interface SignalingDelegate {
        fun onLocalDescription(callId: String, sdp: SessionDescription)
        fun onIceCandidate(callId: String, candidate: IceCandidate)
        fun onCallTimeout(callId: String) // НОВОЕ: таймаут звонка
        fun onConnectionFailed(callId: String) // НОВОЕ: не удалось переподключиться
        fun onVideoUpgradeRequest() // НОВОЕ: запрос перехода на видео
    }
    @Volatile
    var signalingDelegate: SignalingDelegate? = null

    // ---------- internals ----------
    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())

    private var eglBase: EglBase? = null
    private var pcFactory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var remoteVideoTrack: VideoTrack? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var currentCallId: String? = null
    private var currentRole: String? = null

    private val isStarted = AtomicBoolean(false)

    // НОВОЕ: Переподключение
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null

    // НОВОЕ: Таймер таймаута звонка
    private var timeoutRunnable: Runnable? = null

    // renderers
    private var localRendererRef: WeakReference<SurfaceViewRenderer>? = null
    private var remoteRendererRef: WeakReference<SurfaceViewRenderer>? = null
    private val initializedRenderers =
        Collections.newSetFromMap(WeakHashMap<SurfaceViewRenderer, Boolean>())

    // ---------- init ----------
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext

        runCatching { System.loadLibrary("jingle_peerconnection_so") }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(appContext)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseStereoInput(true)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()

        pcFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()

        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.d(TAG, "Initialized")
    }

    // ---------- renderer prep ----------
    fun prepareRenderer(
        view: SurfaceViewRenderer,
        mirror: Boolean,
        overlay: Boolean
    ) {
        val ctx = eglBase?.eglBaseContext ?: return
        val firstInit = !initializedRenderers.contains(view)

        if (firstInit) {
            view.setZOrderOnTop(false)
            view.setZOrderMediaOverlay(overlay)
            view.init(ctx, null)
            view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            view.setEnableHardwareScaler(true)
            initializedRenderers.add(view)
        }
        view.setMirror(mirror)
    }

    // ---------- call start / stop ----------
    @Synchronized
    fun startCall(
        callId: String,
        isVideo: Boolean,
        playRingback: Boolean,
        role: String
    ) {
        require(::appContext.isInitialized) { "Call init(context) first" }

        if (isStarted.get() && currentCallId == callId) {
            Log.d(TAG, "startCall ignored (already running)")
            return
        }
        endCallInternal(true)

        currentCallId = callId
        currentRole = role
        isStarted.set(true)
        _isVideoEnabled.value = isVideo
        _callStartedAtMs.value = null
        _callQuality.value = Quality.Good
        _connectionState.value = ConnectionState.CONNECTING
        reconnectAttempt = 0

        setupAudioForCall(videoMode = isVideo)

        // НОВОЕ: Таймаут для caller
        if (role == "caller") {
            startCallTimeout(callId)
        }

        val iceServers = listOf(
            IceServer.builder("stun:sil-video.ru:3478").createIceServer(),
            IceServer.builder("turn:sil-video.ru:3478?transport=udp")
                .setUsername("melvud").setPassword("berkut14").createIceServer(),
            IceServer.builder("turn:sil-video.ru:3478?transport=tcp")
                .setUsername("melvud").setPassword("berkut14").createIceServer(),
            IceServer.builder("turns:sil-video.ru:443?transport=tcp")
                .setUsername("melvud").setPassword("berkut14").createIceServer()
        )

        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // УЛУЧШЕНИЕ: Настройки для более стабильного соединения
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peer = pcFactory!!.createPeerConnection(rtcConfig, pcObserver) ?: run {
            Log.e(TAG, "PeerConnection = null")
            endCallInternal(true)
            return
        }

        // Local audio
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            optional.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
        }
        audioSource = pcFactory!!.createAudioSource(audioConstraints)
        audioTrack = pcFactory!!.createAudioTrack("AUDIO", audioSource).apply { setEnabled(true) }
        peer!!.addTrack(audioTrack)

        // Local video if needed
        if (isVideo) createAndStartLocalVideo() else disposeVideoChain()

        if (role == "caller") createOffer()
    }

    // НОВОЕ: Таймаут для звонков
    private fun startCallTimeout(callId: String) {
        cancelCallTimeout()
        timeoutRunnable = Runnable {
            if (currentCallId == callId && _connectionState.value != ConnectionState.CONNECTED) {
                Log.w(TAG, "Call timeout reached")
                _connectionState.value = ConnectionState.FAILED
                signalingDelegate?.onCallTimeout(callId)
                endCallInternal(true)
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, CALL_TIMEOUT_MS)
    }

    private fun cancelCallTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    fun endCall() {
        Log.d(TAG, "endCall()")
        endCallInternal(true)
    }

    @Synchronized
    private fun endCallInternal(releaseAll: Boolean) {
        if (!isStarted.get() && !releaseAll) return

        isStarted.set(false)
        _callStartedAtMs.value = null
        _connectionState.value = ConnectionState.DISCONNECTED

        cancelCallTimeout()
        cancelReconnect()

        try { peer?.close() } catch (_: Throwable) {}
        peer = null

        disposeVideoChain()

        runCatching { audioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        audioTrack = null
        audioSource = null

        detachRenderer(true)
        detachRenderer(false)

        teardownAudio()

        currentCallId = null
        currentRole = null
    }

    // ---------- НОВОЕ: Переподключение ----------
    private fun attemptReconnect() {
        if (reconnectAttempt >= RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached")
            _connectionState.value = ConnectionState.FAILED
            signalingDelegate?.onConnectionFailed(currentCallId ?: return)
            endCallInternal(true)
            return
        }

        reconnectAttempt++
        _connectionState.value = ConnectionState.RECONNECTING
        Log.d(TAG, "Reconnect attempt $reconnectAttempt/$RECONNECT_ATTEMPTS")

        // Пробуем перезапустить ICE
        peer?.restartIce()

        // Планируем следующую попытку
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

    // ---------- audio ----------
    private fun setupAudioForCall(videoMode: Boolean) {
        val am = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val afr = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                ).build()
            audioFocusRequest = afr
            am.requestAudioFocus(afr)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        val shouldUseSpeaker = when {
            videoMode -> true
            deviceHasNoEarpiece(am) -> true
            isEmulator() -> true
            hasHeadsetOrBt(am) -> false
            else -> false
        }
        setSpeakerphone(am, shouldUseSpeaker)

        _isMuted.value = false
    }

    private fun teardownAudio() {
        val am = audioManager ?: return
        runCatching { am.mode = AudioManager.MODE_NORMAL }
        runCatching { am.isSpeakerphoneOn = false }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { runCatching { audioManager?.abandonAudioFocusRequest(it) } }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        audioFocusRequest = null
    }

    fun toggleMic() {
        val willMute = !_isMuted.value
        audioTrack?.setEnabled(!willMute)
        _isMuted.value = willMute
    }

    fun toggleSpeaker() {
        val am = audioManager ?: return
        setSpeakerphone(am, !am.isSpeakerphoneOn)
    }

    private fun setSpeakerphone(am: AudioManager, on: Boolean) {
        am.isSpeakerphoneOn = on
        _isSpeakerOn.value = on
        Log.d(TAG, "Speakerphone=$on")
    }

    private fun deviceHasNoEarpiece(am: AudioManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .none { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        } else {
            true
        }
    }

    private fun hasHeadsetOrBt(am: AudioManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                when (it.type) {
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                    else -> false
                }
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

    // ---------- video ----------
    private fun createAndStartLocalVideo() {
        if (videoTrack != null) return
        val factory = pcFactory ?: return

        val capturer = createCameraCapturer() ?: run {
            Log.w(TAG, "No camera capturer")
            _isVideoEnabled.value = false
            return
        }
        videoCapturer = capturer

        surfaceTextureHelper = SurfaceTextureHelper.create("captureSTH", eglBase!!.eglBaseContext)
        videoSource = factory.createVideoSource(false)
        capturer.initialize(surfaceTextureHelper, appContext, videoSource!!.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        videoTrack = factory.createVideoTrack("VIDEO", videoSource).apply { setEnabled(true) }
        _isVideoEnabled.value = true

        peer?.addTrack(videoTrack)

        localRendererRef?.get()?.let { attachLocalSinkTo(it) }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        for (d in enumerator.deviceNames) if (enumerator.isFrontFacing(d))
            return enumerator.createCapturer(d, null)
        for (d in enumerator.deviceNames) if (!enumerator.isFrontFacing(d))
            return enumerator.createCapturer(d, null)
        return null
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    // НОВОЕ: Переход с аудио на видео с уведомлением собеседника
    fun requestVideoUpgrade() {
        if (_isVideoEnabled.value) return

        // Уведомляем другую сторону через signaling
        signalingDelegate?.onVideoUpgradeRequest()

        // Включаем локальное видео
        toggleVideo()
    }

    // НОВОЕ: Принять запрос на видео от собеседника
    fun acceptVideoUpgrade() {
        _videoUpgradeRequest.value = null
        if (!_isVideoEnabled.value) {
            toggleVideo()
        }
    }

    // НОВОЕ: Отклонить запрос на видео
    fun declineVideoUpgrade() {
        _videoUpgradeRequest.value = null
    }

    // НОВОЕ: Обработка входящего запроса на видео
    fun onRemoteVideoUpgradeRequest(fromUsername: String) {
        _videoUpgradeRequest.value = VideoUpgradeRequest(fromUsername)
    }

    fun toggleVideo() {
        val willEnable = !_isVideoEnabled.value
        _isVideoEnabled.value = willEnable
        if (willEnable) {
            if (videoTrack == null) createAndStartLocalVideo() else videoTrack?.setEnabled(true)
            audioManager?.let { setSpeakerphone(it, true) }
        } else {
            videoTrack?.setEnabled(false)
        }
    }

    private fun disposeVideoChain() {
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { surfaceTextureHelper?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        videoCapturer = null
        surfaceTextureHelper = null
        videoTrack = null
        videoSource = null
        _isVideoEnabled.value = false
    }

    // ---------- renderer binding ----------
    fun bindLocalRenderer(view: SurfaceViewRenderer) {
        val prev = localRendererRef?.get()
        if (prev != null && prev !== view) runCatching { videoTrack?.removeSink(prev) }
        localRendererRef = WeakReference(view)
        attachLocalSinkTo(view)
    }

    fun bindRemoteRenderer(view: SurfaceViewRenderer) {
        val prev = remoteRendererRef?.get()
        if (prev != null && prev !== view) runCatching { remoteVideoTrack?.removeSink(prev) }
        remoteRendererRef = WeakReference(view)
        attachRemoteSinkTo(view)
    }

    private fun attachLocalSinkTo(view: SurfaceViewRenderer) {
        postWhenAttached(view) {
            runCatching { videoTrack?.addSink(view) }
            view.invalidate()
        }
    }

    private fun attachRemoteSinkTo(view: SurfaceViewRenderer) {
        postWhenAttached(view) {
            runCatching { remoteVideoTrack?.addSink(view) }
            view.invalidate()
        }
    }

    private fun detachRenderer(local: Boolean) {
        val v = (if (local) localRendererRef else remoteRendererRef)?.get()
        if (v != null) {
            if (local) runCatching { videoTrack?.removeSink(v) }
            else runCatching { remoteVideoTrack?.removeSink(v) }
        }
        if (local) localRendererRef = null else remoteRendererRef = null
    }

    private fun postWhenAttached(view: SurfaceViewRenderer, block: () -> Unit) {
        val runBlock = { if (view.isAttachedToWindow) block() else view.post { if (view.isAttachedToWindow) block() } }
        if (Looper.myLooper() == Looper.getMainLooper()) runBlock() else mainHandler.post { runBlock() }
    }

    // ---------- SDP / ICE ----------
    private fun createOffer() {
        val c = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peer?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                val desc = sdp ?: return
                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        signalingDelegate?.onLocalDescription(currentCallId ?: return, desc)
                    }
                }, desc)
            }
        }, c)
    }

    private fun createAnswer() {
        val c = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peer?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                val desc = sdp ?: return
                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        signalingDelegate?.onLocalDescription(currentCallId ?: return, desc)
                    }
                }, desc)
            }
        }, c)
    }

    fun applyRemoteOffer(sdp: String) {
        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peer?.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() { createAnswer() }
        }, offer)
    }

    fun applyRemoteAnswer(sdp: String) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peer?.setRemoteDescription(object : SdpObserverAdapter() {}, answer)
    }

    fun addRemoteIceCandidate(mid: String, index: Int, cand: String) {
        peer?.addIceCandidate(IceCandidate(mid, index, cand))
    }

    // ---------- remote track hook ----------
    private fun handleRemoteTrack(track: MediaStreamTrack) {
        when (track) {
            is VideoTrack -> {
                val view = remoteRendererRef?.get()
                if (view != null) runCatching { remoteVideoTrack?.removeSink(view) }
                remoteVideoTrack = track
                if (view != null) attachRemoteSinkTo(view)
                track.setEnabled(true)
                Log.d(TAG, "Remote VIDEO track attached")
            }
            else -> {
                if (track.kind() == MediaStreamTrack.AUDIO_TRACK_KIND) {
                    track.setEnabled(true)
                    Log.d(TAG, "Remote AUDIO track enabled")
                }
            }
        }
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            // УЛУЧШЕНИЕ: Обработка состояний соединения
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTED
                    cancelCallTimeout()
                    cancelReconnect()
                }
                PeerConnection.PeerConnectionState.DISCONNECTED -> {
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        // Была связь, потерялась - пробуем переподключиться
                        Log.w(TAG, "Connection lost, attempting reconnect")
                        attemptReconnect()
                    }
                }
                PeerConnection.PeerConnectionState.FAILED -> {
                    _connectionState.value = ConnectionState.FAILED
                    Log.e(TAG, "Connection failed")
                }
                else -> {}
            }
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    if (_callStartedAtMs.value == null) {
                        _callStartedAtMs.value = System.currentTimeMillis()
                    }
                    _connectionState.value = ConnectionState.CONNECTED
                    cancelCallTimeout()
                    cancelReconnect()
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    // Проверяем, не в процессе ли уже переподключения
                    if (_connectionState.value == ConnectionState.CONNECTED) {
                        Log.w(TAG, "ICE disconnected, attempting reconnect")
                        attemptReconnect()
                    }
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    if (_connectionState.value != ConnectionState.FAILED) {
                        Log.e(TAG, "ICE connection failed")
                        _connectionState.value = ConnectionState.FAILED
                    }
                }
                else -> {}
            }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            val c = candidate ?: return
            val id = currentCallId ?: return
            signalingDelegate?.onIceCandidate(id, c)
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            handleRemoteTrack(track)
        }

        override fun onAddStream(stream: org.webrtc.MediaStream?) {
            stream?.videoTracks?.firstOrNull()?.let { handleRemoteTrack(it) }
        }

        override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
        override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}

        override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out org.webrtc.MediaStream>?) {
            receiver?.track()?.let { handleRemoteTrack(it) }
        }

        override fun onRenegotiationNeeded() {}
    }

    fun updateQualityFromStats(bitrateKbps: Int, rttMs: Int, packetLossPct: Int) {
        _callQuality.value = when {
            packetLossPct >= 10 || rttMs >= 800 || bitrateKbps < 200 -> Quality.Poor
            packetLossPct >= 3 || rttMs >= 250 || bitrateKbps < 600 -> Quality.Medium
            else -> Quality.Good
        }
    }

    abstract class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.w(TAG, "SDP create failure: $p0") }
        override fun onSetFailure(p0: String?) { Log.w(TAG, "SDP set failure: $p0") }
    }
}