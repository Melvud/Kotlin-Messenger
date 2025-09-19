@file:Suppress("DEPRECATION")

package com.example.messenger_app.webrtc

import android.content.Context
import android.media.AudioAttributes
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

object WebRtcCallManager {

    private const val TAG = "WebRtcCallManager"

    // ---------- public UI state ----------
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn

    private val _isVideoEnabled = MutableStateFlow(false)
    val isVideoEnabled: StateFlow<Boolean> = _isVideoEnabled

    private val _callStartedAtMs = MutableStateFlow<Long?>(null)
    val callStartedAtMs: StateFlow<Long?> = _callStartedAtMs

    // ---------- signaling ----------
    interface SignalingDelegate {
        fun onLocalDescription(callId: String, sdp: SessionDescription)
        fun onIceCandidate(callId: String, candidate: IceCandidate)
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

    private val isStarted = AtomicBoolean(false)

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
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ true
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

    // ---------- renderer prep (call from UI before addSink) ----------
    fun prepareRenderer(
        view: SurfaceViewRenderer,
        mirror: Boolean,
        overlay: Boolean // onTop всегда false
    ) {
        val ctx = eglBase?.eglBaseContext ?: return
        val firstInit = !initializedRenderers.contains(view)

        if (firstInit) {
            // ВАЖНО: порядок — сначала З-order, потом init()
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
        isStarted.set(true)
        _isVideoEnabled.value = isVideo
        _callStartedAtMs.value = null

        setupAudioForCall(videoMode = isVideo)

        // ---- Единственное изменение: актуальный STUN/TURN для sil-video.ru ----
        val iceServers = listOf(
            IceServer.builder("stun:sil-video.ru:3478")
                .createIceServer(),
            IceServer.builder("turn:sil-video.ru:3478?transport=udp")
                .setUsername("melvud").setPassword("berkut14").createIceServer(),
            IceServer.builder("turn:sil-video.ru:3478?transport=tcp")
                .setUsername("melvud").setPassword("berkut14").createIceServer(),
            // Ключевое для сетей в РФ — TLS на 443 поверх TCP:
            IceServer.builder("turns:sil-video.ru:443?transport=tcp")
                .setUsername("melvud").setPassword("berkut14").createIceServer()
        )
        // ----------------------------------------------------------------------

        val rtcConfig = RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peer = pcFactory!!.createPeerConnection(rtcConfig, pcObserver) ?: run {
            Log.e(TAG, "PeerConnection = null")
            endCallInternal(true)
            return
        }

        // Local audio
        val audioConstraints = MediaConstraints().apply {
            // Улучшение качества звука
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

    fun endCall() {
        Log.d(TAG, "endCall()")
        endCallInternal(true)
    }

    @Synchronized
    private fun endCallInternal(releaseAll: Boolean) {
        if (!isStarted.get() && !releaseAll) return

        isStarted.set(false)
        _callStartedAtMs.value = null

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
        val useSpeaker = videoMode
        am.isSpeakerphoneOn = useSpeaker
        _isSpeakerOn.value = useSpeaker
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
        val newState = !am.isSpeakerphoneOn
        am.isSpeakerphoneOn = newState
        _isSpeakerOn.value = newState
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

        // If renderer already bound — attach immediately
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

    fun toggleVideo() {
        val willEnable = !_isVideoEnabled.value
        _isVideoEnabled.value = willEnable
        if (willEnable) {
            if (videoTrack == null) createAndStartLocalVideo() else videoTrack?.setEnabled(true)
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
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}

        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            if (state == PeerConnection.IceConnectionState.CONNECTED) {
                if (_callStartedAtMs.value == null) _callStartedAtMs.value = System.currentTimeMillis()
            }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            val c = candidate ?: return
            val id = currentCallId ?: return
            signalingDelegate?.onIceCandidate(id, c)
        }

        // Unified Plan
        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            handleRemoteTrack(track)
        }

        // Plan B fallback
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

    abstract class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(p0: String?) { Log.w(TAG, "SDP create failure: $p0") }
        override fun onSetFailure(p0: String?) { Log.w(TAG, "SDP set failure: $p0") }
    }
}
