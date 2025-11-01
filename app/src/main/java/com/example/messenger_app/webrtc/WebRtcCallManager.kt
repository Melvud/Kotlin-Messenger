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
import com.google.firebase.Timestamp
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
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
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

object WebRtcCallManager {

    private const val TAG = "WebRtcCallManager"
    private const val CALL_TIMEOUT_MS = 45_000L
    private const val RECONNECT_ATTEMPTS = 5
    private const val RECONNECT_DELAY_MS = 2_000L
    private const val STREAM_ID = "ANTIMAX_STREAM"

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn

    private val _isVideoEnabled = MutableStateFlow(false)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled

    private val _isRemoteVideoEnabled = MutableStateFlow(false)
    val isRemoteVideoEnabled: StateFlow<Boolean> = _isRemoteVideoEnabled

    // ✅ НОВОЕ: Синхронизированный timestamp начала звонка
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

    interface SignalingDelegate {
        fun onLocalDescription(callId: String, sdp: SessionDescription)
        fun onIceCandidate(callId: String, candidate: IceCandidate)
        fun onCallTimeout(callId: String)
        fun onConnectionFailed(callId: String)
        fun onVideoUpgradeRequest()
        // ✅ НОВОЕ: Callback для синхронизации времени
        fun onCallStarted(startTimeMs: Long)
    }

    @Volatile
    var signalingDelegate: SignalingDelegate? = null

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())

    private var eglBase: EglBase? = null
    private var pcFactory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioSender: RtpSender? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var videoSender: RtpSender? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var currentCallId: String? = null
    private var currentRole: String? = null
    private val isStarted = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var reconnectRunnable: Runnable? = null
    private var timeoutRunnable: Runnable? = null

    private var localRendererRef: WeakReference<SurfaceViewRenderer>? = null
    private var remoteRendererRef: WeakReference<SurfaceViewRenderer>? = null
    private val initializedRenderers = Collections.newSetFromMap(WeakHashMap<SurfaceViewRenderer, Boolean>())

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private val offerCreated = AtomicBoolean(false)
    private val isRenegotiating = AtomicBoolean(false)

    fun init(context: Context) {
        if (::appContext.isInitialized) {
            Log.d(TAG, "Already initialized")
            return
        }

        Log.d(TAG, "🚀 INITIALIZING WebRTC")
        appContext = context.applicationContext

        try {
            System.loadLibrary("jingle_peerconnection_so")
            Log.d(TAG, "✅ Native library loaded")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load native library", e)
        }

        val options = PeerConnectionFactory.InitializationOptions
            .builder(appContext)
            .setEnableInternalTracer(false)
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)
        Log.d(TAG, "✅ PeerConnectionFactory initialized")

        eglBase = EglBase.create()
        Log.d(TAG, "✅ EglBase created")

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext,
            true,
            true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .createAudioDeviceModule()

        pcFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            })
            .createPeerConnectionFactory()

        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        Log.d(TAG, "✅ WebRTC INITIALIZATION COMPLETE")
    }

    @Synchronized
    fun startCall(
        callId: String,
        isVideo: Boolean,
        playRingback: Boolean,
        role: String
    ) {
        require(::appContext.isInitialized) { "Call init(context) first!" }
        require(role == "caller" || role == "callee") { "Invalid role: $role" }

        Log.d(TAG, "📞 START CALL: id=$callId, video=$isVideo, role=$role")

        if (isStarted.get() && currentCallId == callId) {
            Log.w(TAG, "Call already running, ignoring")
            return
        }

        if (isStarted.get() && currentCallId != callId) {
            Log.w(TAG, "Ending previous call: $currentCallId")
            endCallInternal(false)
        }

        currentCallId = callId
        currentRole = role
        isStarted.set(true)
        _isVideoEnabled.value = isVideo
        _isRemoteVideoEnabled.value = false
        _callStartedAtMs.value = null
        _callQuality.value = Quality.Good
        _connectionState.value = ConnectionState.CONNECTING
        reconnectAttempt = 0
        remoteDescriptionSet = false
        pendingIceCandidates.clear()
        offerCreated.set(false)
        isRenegotiating.set(false)

        setupAudioForCall(videoMode = isVideo)

        if (role == "caller") {
            startCallTimeout(callId)
        }

        val iceServers = listOf(
            IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),

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
                .createIceServer(),

            IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer()
        )

        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceConnectionReceivingTimeout = 2000
            iceBackupCandidatePairPingInterval = 1000
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
        }

        Log.d(TAG, "Creating PeerConnection...")
        peer = pcFactory!!.createPeerConnection(rtcConfig, pcObserver)

        if (peer == null) {
            Log.e(TAG, "❌ FATAL: Failed to create PeerConnection")
            _connectionState.value = ConnectionState.FAILED
            endCallInternal(false)
            return
        }

        Log.d(TAG, "✅ PeerConnection created")

        setupAudioTrack()

        if (isVideo) {
            createAndStartLocalVideoSync()
        }

        Log.d(TAG, if (role == "caller") "Role=CALLER: waiting for onRenegotiationNeeded" else "Role=CALLEE: waiting for offer")
        Log.d(TAG, "✅ START CALL COMPLETE")
    }

    // ✅ НОВОЕ: Установка времени начала звонка из Firestore
    fun setCallStartTime(startTimeMs: Long) {
        if (_callStartedAtMs.value == null) {
            _callStartedAtMs.value = startTimeMs
            Log.d(TAG, "✅ Call start time set to: $startTimeMs")
        }
    }

    fun endCall() {
        Log.d(TAG, "endCall() called")
        endCallInternal(false)
    }

    @Synchronized
    private fun endCallInternal(disposeAudioModule: Boolean) {
        if (!isStarted.get()) {
            Log.d(TAG, "Call not started, nothing to end")
            return
        }

        Log.d(TAG, "🔚 END CALL")

        isStarted.set(false)
        _callStartedAtMs.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        offerCreated.set(false)
        isRenegotiating.set(false)

        cancelCallTimeout()
        cancelReconnect()

        try {
            audioTrack?.setEnabled(false)
            videoTrack?.setEnabled(false)
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling tracks", e)
        }

        try {
            audioSender?.let { peer?.removeTrack(it) }
            videoSender?.let { peer?.removeTrack(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing senders", e)
        }

        peer?.let {
            try {
                it.close()
                Log.d(TAG, "✅ PeerConnection closed")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing peer", e)
            }
        }
        peer = null

        disposeVideoChain()

        try {
            audioTrack?.dispose()
            audioSource?.dispose()
            audioTrack = null
            audioSource = null
            audioSender = null
            Log.d(TAG, "✅ Audio disposed")
        } catch (e: Exception) {
            Log.e(TAG, "Error disposing audio", e)
        }

        detachRenderer(true)
        detachRenderer(false)
        remoteVideoTrack = null

        teardownAudio()

        currentCallId = null
        currentRole = null
        remoteDescriptionSet = false
        pendingIceCandidates.clear()
        _isRemoteVideoEnabled.value = false

        Log.d(TAG, "✅ END CALL COMPLETE")
    }

    private fun setupAudioTrack() {
        Log.d(TAG, "🎤 SETTING UP AUDIO TRACK")

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            optional.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
        }

        audioSource = pcFactory!!.createAudioSource(audioConstraints)
        audioTrack = pcFactory!!.createAudioTrack("AUDIO_${System.currentTimeMillis()}", audioSource)
        audioTrack?.setEnabled(true)

        audioSender = peer?.addTrack(audioTrack, listOf(STREAM_ID))

        Log.d(TAG, "✅ Audio track created: ${audioTrack?.id()}")
    }

    private fun setupAudioForCall(videoMode: Boolean) {
        val am = audioManager ?: return
        Log.d(TAG, "Setting up audio for call (videoMode=$videoMode)")

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

        Log.d(TAG, "✅ Audio setup complete: speaker=$shouldUseSpeaker")
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
        Log.d(TAG, "✅ Audio teardown complete")
    }

    fun toggleMic() {
        val willMute = !_isMuted.value
        audioTrack?.setEnabled(!willMute)
        _isMuted.value = willMute
        Log.d(TAG, "🎤 Microphone ${if (willMute) "MUTED" else "UNMUTED"}")
    }

    fun toggleSpeaker() {
        val am = audioManager ?: return
        setSpeakerphone(am, !am.isSpeakerphoneOn)
    }

    private fun setSpeakerphone(am: AudioManager, on: Boolean) {
        try {
            am.isSpeakerphoneOn = on
            _isSpeakerOn.value = on
            Log.d(TAG, "🔊 Speakerphone ${if (on) "ON" else "OFF"}")
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

    private fun createAndStartLocalVideoSync() {
        Log.d(TAG, "📹 CREATING LOCAL VIDEO (SYNC)")

        val capturer = createCameraCapturer() ?: run {
            Log.e(TAG, "❌ Failed to create camera capturer")
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

        capturer.startCapture(1280, 720, 30)
        Log.d(TAG, "✅ Camera capture started: 1280x720@30fps")

        videoTrack = pcFactory!!.createVideoTrack("VIDEO_${System.currentTimeMillis()}", videoSource)
        videoTrack?.setEnabled(true)

        videoSender = peer?.addTrack(videoTrack, listOf(STREAM_ID))

        _isVideoEnabled.value = true

        localRendererRef?.get()?.let {
            Log.d(TAG, "Attaching video to local renderer")
            attachLocalSinkTo(it)
        }

        Log.d(TAG, "✅ Video track created: ${videoTrack?.id()}")
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)

        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "Using front camera: $deviceName")
                return enumerator.createCapturer(deviceName, null)
            }
        }

        for (deviceName in enumerator.deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "Using back camera: $deviceName")
                return enumerator.createCapturer(deviceName, null)
            }
        }

        Log.e(TAG, "❌ No camera found!")
        return null
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                Log.d(TAG, "✅ Camera switched: front=$isFrontFacing")
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.e(TAG, "❌ Camera switch error: $errorDescription")
            }
        })
    }

    fun toggleVideo() {
        val willEnable = !_isVideoEnabled.value
        Log.d(TAG, "📹 TOGGLE VIDEO: $willEnable (connected=${_connectionState.value == ConnectionState.CONNECTED})")

        val p = peer
        if (p == null) {
            Log.e(TAG, "❌ Cannot toggle video: peer is null")
            return
        }

        if (willEnable) {
            if (videoTrack == null) {
                createAndStartLocalVideoSync()

                if (_connectionState.value == ConnectionState.CONNECTED) {
                    Log.d(TAG, "✅ Connection active, triggering renegotiation")
                    mainHandler.postDelayed({
                        triggerRenegotiation()
                    }, 300)
                }
            } else {
                videoTrack?.setEnabled(true)
                _isVideoEnabled.value = true
            }
            audioManager?.let { setSpeakerphone(it, true) }
        } else {
            videoTrack?.setEnabled(false)
            _isVideoEnabled.value = false

            if (_connectionState.value == ConnectionState.CONNECTED) {
                Log.d(TAG, "✅ Connection active, triggering renegotiation after disabling video")
                mainHandler.postDelayed({
                    triggerRenegotiation()
                }, 300)
            }
        }
    }

    private fun triggerRenegotiation() {
        if (!isStarted.get()) {
            Log.w(TAG, "Cannot renegotiate: call not started")
            return
        }

        if (!isRenegotiating.compareAndSet(false, true)) {
            Log.w(TAG, "⚠️ Renegotiation already in progress, skipping")
            return
        }

        Log.d(TAG, "🔄 TRIGGERING RENEGOTIATION")

        val p = peer ?: run {
            Log.e(TAG, "Cannot renegotiate: peer is null")
            isRenegotiating.set(false)
            return
        }

        offerCreated.set(false)

        mainHandler.postDelayed({
            createOffer()
        }, 100)
    }

    private fun disposeVideoChain() {
        Log.d(TAG, "Disposing video chain...")

        try {
            videoSender?.let { peer?.removeTrack(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing video sender", e)
        }

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
        videoSender = null
        _isVideoEnabled.value = false

        Log.d(TAG, "✅ Video chain disposed")
    }

    fun requestVideoUpgrade() {
        if (_isVideoEnabled.value) {
            Log.d(TAG, "Video already enabled")
            return
        }

        Log.d(TAG, "📹 REQUESTING VIDEO UPGRADE")

        signalingDelegate?.onVideoUpgradeRequest()
        toggleVideo()
    }

    fun acceptVideoUpgrade() {
        Log.d(TAG, "✅ ACCEPTING VIDEO UPGRADE")

        _videoUpgradeRequest.value = null

        if (!_isVideoEnabled.value) {
            toggleVideo()
        }
    }

    fun declineVideoUpgrade() {
        Log.d(TAG, "❌ Declining video upgrade")
        _videoUpgradeRequest.value = null
    }

    fun onRemoteVideoUpgradeRequest(fromUsername: String) {
        Log.d(TAG, "📹 REMOTE VIDEO UPGRADE REQUEST from: $fromUsername")

        _videoUpgradeRequest.value = VideoUpgradeRequest(fromUsername)
    }

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
            Log.d(TAG, "✅ Renderer initialized: overlay=$overlay, mirror=$mirror")
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
        Log.d(TAG, "✅ Local renderer bound")
    }

    fun bindRemoteRenderer(view: SurfaceViewRenderer) {
        Log.d(TAG, "🎬 BIND REMOTE RENDERER")

        val prev = remoteRendererRef?.get()
        if (prev != null && prev !== view) {
            try {
                remoteVideoTrack?.removeSink(prev)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing old remote sink", e)
            }
        }

        remoteRendererRef = WeakReference(view)

        if (remoteVideoTrack != null) {
            attachRemoteSinkTo(view)
        }

        Log.d(TAG, "✅ Remote renderer bound")
    }

    private fun attachLocalSinkTo(view: SurfaceViewRenderer) {
        postWhenAttached(view) {
            try {
                videoTrack?.addSink(view)
                view.invalidate()
                Log.d(TAG, "✅ Local sink attached")
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
                Log.d(TAG, "✅ Remote sink attached")
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
                Log.d(TAG, "✅ ${if (local) "Local" else "Remote"} renderer detached")
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

    private fun fixSdpSetup(sdp: String, role: String): String {
        Log.d(TAG, "🔧 Fixing SDP setup attribute for role: $role")

        return when (role) {
            "caller" -> {
                sdp.replace("a=setup:passive", "a=setup:actpass")
                    .replace("a=setup:active", "a=setup:actpass")
            }
            "callee" -> {
                sdp.replace("a=setup:actpass", "a=setup:active")
                    .replace("a=setup:passive", "a=setup:active")
            }
            else -> sdp
        }
    }

    private fun createOffer() {
        if (!offerCreated.compareAndSet(false, true)) {
            Log.d(TAG, "⚠️ Offer already created, skipping")
            return
        }

        Log.d(TAG, "Creating offer...")

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peer?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                val desc = sdp ?: run {
                    Log.e(TAG, "SDP is null in onCreateSuccess")
                    offerCreated.set(false)
                    isRenegotiating.set(false)
                    return
                }

                val fixedSdp = fixSdpSetup(desc.description, "caller")
                val fixedDesc = SessionDescription(desc.type, fixedSdp)

                Log.d(TAG, "✅ Offer created, setting local description")

                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d(TAG, "✅ Local description set, sending to signaling")
                        signalingDelegate?.onLocalDescription(currentCallId ?: return, fixedDesc)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "❌ Failed to set local description: $error")
                        offerCreated.set(false)
                        isRenegotiating.set(false)
                    }
                }, fixedDesc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "❌ Failed to create offer: $error")
                offerCreated.set(false)
                isRenegotiating.set(false)
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
                    isRenegotiating.set(false)
                    return
                }

                val fixedSdp = fixSdpSetup(desc.description, "callee")
                val fixedDesc = SessionDescription(desc.type, fixedSdp)

                Log.d(TAG, "✅ Answer created, setting local description")

                peer?.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        Log.d(TAG, "✅ Local description set, sending to signaling")
                        signalingDelegate?.onLocalDescription(currentCallId ?: return, fixedDesc)
                        isRenegotiating.set(false)
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "❌ Failed to set local description: $error")
                        isRenegotiating.set(false)
                    }
                }, fixedDesc)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "❌ Failed to create answer: $error")
                isRenegotiating.set(false)
            }
        }, constraints)
    }

    fun applyRemoteOffer(sdp: String) {
        Log.d(TAG, "📥 APPLY REMOTE OFFER (length: ${sdp.length})")

        val p = peer ?: run {
            Log.e(TAG, "❌ FATAL: peer is null!")
            return
        }

        val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)

        p.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.d(TAG, "✅ Remote offer set successfully")

                if (!remoteDescriptionSet) {
                    remoteDescriptionSet = true

                    if (pendingIceCandidates.isNotEmpty()) {
                        Log.d(TAG, "Adding ${pendingIceCandidates.size} pending ICE candidates")
                        pendingIceCandidates.forEach { candidate ->
                            p.addIceCandidate(candidate)
                        }
                        pendingIceCandidates.clear()
                    }
                }

                createAnswer()
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "❌ Failed to set remote offer: $error")
                isRenegotiating.set(false)
            }
        }, offer)
    }

    fun applyRemoteAnswer(sdp: String) {
        Log.d(TAG, "📥 APPLY REMOTE ANSWER (length: ${sdp.length})")

        val p = peer ?: run {
            Log.e(TAG, "❌ FATAL: peer is null!")
            return
        }

        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)

        p.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.d(TAG, "✅ Remote answer set successfully")
                remoteDescriptionSet = true
                isRenegotiating.set(false)

                if (pendingIceCandidates.isNotEmpty()) {
                    Log.d(TAG, "Adding ${pendingIceCandidates.size} pending ICE candidates")
                    pendingIceCandidates.forEach { candidate ->
                        p.addIceCandidate(candidate)
                    }
                    pendingIceCandidates.clear()
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "❌ Failed to set remote answer: $error")
                isRenegotiating.set(false)
            }
        }, answer)
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
        Log.d(TAG, "✅ ICE candidate added: mid=$mid, index=$index")
    }

    private val pcObserver = object : PeerConnection.Observer {

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "📡 Signaling state: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "🧊 ICE connection state: $state")

            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    mainHandler.post {
                        // ✅ FIX: Сохраняем время в Firestore через delegate
                        if (_callStartedAtMs.value == null) {
                            val startTime = System.currentTimeMillis()
                            _callStartedAtMs.value = startTime

                            // Сообщаем CallScreen что нужно сохранить время в Firestore
                            signalingDelegate?.onCallStarted(startTime)

                            Log.d(TAG, "✅ Call started at: $startTime")
                        }
                        _connectionState.value = ConnectionState.CONNECTED
                        cancelCallTimeout()
                        cancelReconnect()
                        Log.d(TAG, "✅ Call CONNECTED")
                    }
                }

                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    mainHandler.post {
                        if (_connectionState.value == ConnectionState.CONNECTED) {
                            Log.w(TAG, "⚠️ Connection lost, attempting reconnect...")
                            attemptReconnect()
                        }
                    }
                }

                PeerConnection.IceConnectionState.FAILED -> {
                    mainHandler.post {
                        Log.e(TAG, "❌ ICE connection FAILED")
                        _connectionState.value = ConnectionState.FAILED
                    }
                }

                else -> {}
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "🧊 ICE receiving: $receiving")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "🧊 ICE gathering: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            Log.d(TAG, "🧊 Local ICE candidate: ${candidate.sdpMid}:${candidate.sdpMLineIndex}")
            signalingDelegate?.onIceCandidate(currentCallId ?: return, candidate)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
            Log.d(TAG, "🧊 ICE candidates removed: ${candidates?.size}")
        }

        override fun onAddStream(stream: MediaStream?) {
            Log.d(TAG, "📺 onAddStream (not used in Unified Plan)")
        }

        override fun onRemoveStream(stream: MediaStream?) {
            Log.d(TAG, "📺 onRemoveStream")
        }

        override fun onDataChannel(dataChannel: org.webrtc.DataChannel?) {
            Log.d(TAG, "📡 onDataChannel: ${dataChannel?.label()}")
        }

        override fun onRenegotiationNeeded() {
            Log.d(TAG, "🔄 onRenegotiationNeeded")
            mainHandler.post {
                if (peer != null && isStarted.get() && currentRole == "caller") {
                    if (!offerCreated.get() && !isRenegotiating.get()) {
                        Log.d(TAG, "Handling renegotiation - creating new offer")
                        createOffer()
                    } else {
                        Log.d(TAG, "Skipping renegotiation: offer already created or renegotiation in progress")
                    }
                } else {
                    Log.d(TAG, "Skipping renegotiation: peer=$peer, started=${isStarted.get()}, role=$currentRole")
                }
            }
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track() ?: return

            Log.d(TAG, "🎬 ON ADD TRACK: ${track.kind()} (id: ${track.id()})")

            mainHandler.post {
                when (track) {
                    is VideoTrack -> {
                        Log.d(TAG, "📹 Remote VIDEO track received")

                        if (remoteVideoTrack != null) {
                            try {
                                val view = remoteRendererRef?.get()
                                view?.let { remoteVideoTrack?.removeSink(it) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error removing old sink", e)
                            }
                        }

                        remoteVideoTrack = track
                        track.setEnabled(true)
                        _isRemoteVideoEnabled.value = true

                        Log.d(TAG, "✅ Remote video track set, enabled: ${track.enabled()}")

                        mainHandler.postDelayed({
                            val view = remoteRendererRef?.get()
                            if (view != null) {
                                attachRemoteSinkTo(view)
                                Log.d(TAG, "✅ Remote video attached to renderer")
                            } else {
                                Log.w(TAG, "⚠️ Remote renderer view is null!")
                            }
                        }, 100)
                    }

                    is AudioTrack -> {
                        track.setEnabled(true)
                        Log.d(TAG, "✅ Remote AUDIO track enabled")
                    }
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            Log.d(TAG, "🎬 ON TRACK: ${track.kind()} (id: ${track.id()})")
        }
    }

    private fun attemptReconnect() {
        if (reconnectAttempt >= RECONNECT_ATTEMPTS) {
            Log.e(TAG, "❌ Max reconnect attempts reached")
            mainHandler.post {
                _connectionState.value = ConnectionState.FAILED
                signalingDelegate?.onConnectionFailed(currentCallId ?: return@post)
                endCallInternal(false)
            }
            return
        }

        reconnectAttempt++
        Log.d(TAG, "🔄 Reconnect attempt $reconnectAttempt/$RECONNECT_ATTEMPTS")

        mainHandler.post {
            _connectionState.value = ConnectionState.RECONNECTING
        }

        reconnectRunnable = Runnable {
            if (peer == null || !isStarted.get()) {
                Log.w(TAG, "Cannot reconnect: call ended or peer null")
                return@Runnable
            }

            Log.d(TAG, "Restarting ICE...")
            peer?.restartIce()
        }

        mainHandler.postDelayed(reconnectRunnable!!, RECONNECT_DELAY_MS)
    }

    private fun cancelReconnect() {
        reconnectRunnable?.let {
            mainHandler.removeCallbacks(it)
            reconnectRunnable = null
        }
        reconnectAttempt = 0
    }

    private fun startCallTimeout(callId: String) {
        timeoutRunnable = Runnable {
            if (_connectionState.value != ConnectionState.CONNECTED) {
                Log.e(TAG, "⏰ Call timeout!")
                signalingDelegate?.onCallTimeout(callId)
                endCallInternal(false)
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, CALL_TIMEOUT_MS)
    }

    private fun cancelCallTimeout() {
        timeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            timeoutRunnable = null
        }
    }

    private abstract class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}