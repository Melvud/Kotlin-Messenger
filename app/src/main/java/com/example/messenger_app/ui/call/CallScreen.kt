package com.example.messenger_app.ui.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.example.messenger_app.data.CallsRepository
import com.example.messenger_app.push.CallActionReceiver
import com.example.messenger_app.push.CallService
import com.example.messenger_app.push.NotificationHelper
import com.example.messenger_app.push.OngoingCallStore
import com.example.messenger_app.webrtc.WebRtcCallManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

/**
 * ═══════════════════════════════════════════════════════════════
 *          ИСПРАВЛЕННЫЙ CALL SCREEN БЕЗ RECOMPOSITION
 * ═══════════════════════════════════════════════════════════════
 */
@Composable
fun CallScreen(
    callId: String,
    isVideo: Boolean,
    playRingback: Boolean = false,
    onNavigateBack: () -> Unit,
    otherUsername: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val role = remember { if (playRingback) "caller" else "callee" }

    // WebRTC States
    val isMuted by WebRtcCallManager.isMuted.collectAsState()
    val isSpeakerOn by WebRtcCallManager.isSpeakerOn.collectAsState()
    val isLocalVideoEnabled by WebRtcCallManager.isVideoEnabled.collectAsState()
    val isRemoteVideoEnabled by WebRtcCallManager.isRemoteVideoEnabled.collectAsState()
    val connectionState by WebRtcCallManager.connectionState.collectAsState()
    val callQuality by WebRtcCallManager.callQuality.collectAsState()
    val videoUpgradeRequest by WebRtcCallManager.videoUpgradeRequest.collectAsState()

    // Local States
    var callEnded by remember { mutableStateOf(false) }
    var showEndCallDialog by remember { mutableStateOf(false) }
    var videoSwapped by remember { mutableStateOf(false) }
    var startedAtMillis by remember { mutableStateOf<Long?>(null) }
    var peerName by remember { mutableStateOf(otherUsername ?: "Собеседник") }

    // Firestore
    val db = remember { FirebaseFirestore.getInstance() }
    val repo = remember { CallsRepository(FirebaseAuth.getInstance(), db) }

    // SDP flags
    val offerApplied = remember { mutableStateOf(false) }
    val answerApplied = remember { mutableStateOf(false) }

    // Timer
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startedAtMillis) {
        if (startedAtMillis != null) {
            while (!callEnded) {
                delay(1000)
                nowMs = System.currentTimeMillis()
            }
        }
    }

    val elapsedMillis = if (startedAtMillis != null && !callEnded) {
        (nowMs - startedAtMillis!!).coerceAtLeast(0)
    } else 0L

    // ==================== INIT ONCE ====================

    DisposableEffect(Unit) {
        Log.d("CallScreen", """
            ════════════════════════════════════════
            🚀 CALL SCREEN STARTED
            callId: $callId
            isVideo: $isVideo
            role: $role
            ════════════════════════════════════════
        """.trimIndent())

        // Clear notification
        NotificationHelper.cancelIncomingCall(context, callId)

        // Initialize WebRTC ONCE
        WebRtcCallManager.init(context)

        // Start call
        WebRtcCallManager.startCall(
            callId = callId,
            isVideo = isVideo,
            playRingback = playRingback,
            role = role
        )

        // Start service
        CallService.start(
            ctx = context,
            callId = callId,
            username = peerName,
            isVideo = isVideo,
            openUi = false,
            playRingback = playRingback
        )

        onDispose {
            Log.d("CallScreen", "🧹 CallScreen disposed")
        }
    }

    // ==================== SIGNALING ====================

    DisposableEffect(Unit) {
        WebRtcCallManager.signalingDelegate = object : WebRtcCallManager.SignalingDelegate {
            override fun onLocalDescription(callId: String, sdp: org.webrtc.SessionDescription) {
                Log.d("CallScreen", "📤 SDP: ${sdp.type}")
                scope.launch(Dispatchers.IO) {
                    if (role == "caller") {
                        repo.setOffer(callId, sdp.description, "offer")
                    } else {
                        repo.setAnswer(callId, sdp.description, "answer")
                    }
                }
            }

            override fun onIceCandidate(callId: String, candidate: org.webrtc.IceCandidate) {
                Log.d("CallScreen", "📤 ICE: ${candidate.sdpMid}:${candidate.sdpMLineIndex}")
                val who = if (role == "caller") "caller" else "callee"
                val map = hashMapOf(
                    "sdpMid" to (candidate.sdpMid ?: "0"),
                    "sdpMLineIndex" to candidate.sdpMLineIndex,
                    "candidate" to candidate.sdp,
                    "createdAt" to FieldValue.serverTimestamp()
                )
                repo.candidatesCollection(callId, who).add(map)
            }

            override fun onCallTimeout(callId: String) {
                scope.launch(Dispatchers.IO) {
                    repo.updateStatus(callId, "timeout")
                    db.collection("calls").document(callId)
                        .update("endedAt", FieldValue.serverTimestamp())
                }
            }

            override fun onConnectionFailed(callId: String) {
                scope.launch(Dispatchers.IO) {
                    repo.updateStatus(callId, "failed")
                    db.collection("calls").document(callId)
                        .update("endedAt", FieldValue.serverTimestamp())
                }
            }

            override fun onVideoUpgradeRequest() {
                scope.launch(Dispatchers.IO) {
                    db.collection("calls").document(callId)
                        .update("videoUpgradeRequest", FieldValue.serverTimestamp())
                }
            }
        }

        onDispose {
            WebRtcCallManager.signalingDelegate = null
        }
    }

    // ==================== FIRESTORE ====================

    DisposableEffect(Unit) {
        val callDoc = db.collection("calls").document(callId)

        val docReg = callDoc.addSnapshotListener { snap, error ->
            if (error != null) return@addSnapshotListener
            val data = snap?.data ?: return@addSnapshotListener

            // Get peer name
            if (peerName == "Собеседник") {
                val name = when (role) {
                    "caller" -> (data["calleeUsername"] ?: data["toUsername"]) as? String
                    else -> (data["callerUsername"] ?: data["fromUsername"]) as? String
                } ?: otherUsername ?: "Собеседник"

                if (name != peerName) {
                    peerName = name
                }
            }

            // Handle SDP
            if (role == "callee") {
                val offer = (data["offer"] as? Map<*, *>)?.get("sdp") as? String
                if (!offer.isNullOrBlank() && !offerApplied.value) {
                    Log.d("CallScreen", "📥 Applying OFFER")
                    offerApplied.value = true
                    WebRtcCallManager.applyRemoteOffer(offer)
                }
            }

            if (role == "caller") {
                val answer = (data["answer"] as? Map<*, *>)?.get("sdp") as? String
                if (!answer.isNullOrBlank() && !answerApplied.value) {
                    Log.d("CallScreen", "📥 Applying ANSWER")
                    answerApplied.value = true
                    WebRtcCallManager.applyRemoteAnswer(answer)
                    CallService.stopRingback(context)
                }
            }

            // Handle startedAt
            val ts = data["startedAt"]
            if (ts is com.google.firebase.Timestamp) {
                if (startedAtMillis == null) {
                    startedAtMillis = ts.toDate().time
                    CallService.stopRingback(context)
                }
            }

            // Video upgrade
            val videoUpgradeTs = data["videoUpgradeRequest"]
            if (videoUpgradeTs != null && role == "callee") {
                val fromUser = (data["callerUsername"] ?: data["fromUsername"] ?: "Собеседник") as String
                WebRtcCallManager.onRemoteVideoUpgradeRequest(fromUser)
            }

            // End call
            if (data["endedAt"] != null && !callEnded) {
                callEnded = true
                WebRtcCallManager.endCall()
                CallService.stopRingback(context)
                context.stopService(Intent(context, CallService::class.java))
                OngoingCallStore.clear(context)
                scope.launch {
                    delay(1500)
                    onNavigateBack()
                }
            }
        }

        val iceColl = if (role == "caller") "calleeCandidates" else "callerCandidates"
        val iceReg = callDoc.collection(iceColl)
            .addSnapshotListener { snap, error ->
                if (error != null) return@addSnapshotListener

                snap?.documentChanges?.forEach { dc ->
                    if (dc.type == DocumentChange.Type.ADDED) {
                        val d = dc.document.data
                        val mid = d["sdpMid"] as? String ?: "0"
                        val idx = (d["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                        val cand = d["candidate"] as? String ?: return@forEach

                        WebRtcCallManager.addRemoteIceCandidate(mid, idx, cand)
                    }
                }
            }

        onDispose {
            docReg.remove()
            iceReg.remove()
        }
    }

    // ==================== BROADCAST ====================

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    CallActionReceiver.ACTION_INTERNAL_HANGUP -> {
                        callEnded = true
                        WebRtcCallManager.endCall()
                        onNavigateBack()
                    }
                    CallActionReceiver.ACTION_INTERNAL_TOGGLE_MUTE -> WebRtcCallManager.toggleMic()
                    CallActionReceiver.ACTION_INTERNAL_TOGGLE_SPEAKER -> WebRtcCallManager.toggleSpeaker()
                    CallActionReceiver.ACTION_INTERNAL_TOGGLE_VIDEO -> WebRtcCallManager.toggleVideo()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(CallActionReceiver.ACTION_INTERNAL_HANGUP)
            addAction(CallActionReceiver.ACTION_INTERNAL_TOGGLE_MUTE)
            addAction(CallActionReceiver.ACTION_INTERNAL_TOGGLE_SPEAKER)
            addAction(CallActionReceiver.ACTION_INTERNAL_TOGGLE_VIDEO)
        }

        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // ==================== CLEANUP ====================

    DisposableEffect(Unit) {
        onDispose {
            if (!callEnded) {
                scope.launch(Dispatchers.IO) {
                    db.collection("calls").document(callId)
                        .update("endedAt", FieldValue.serverTimestamp())
                }
            }
        }
    }

    // ==================== BACK ====================

    BackHandler {
        showEndCallDialog = true
    }

    // ==================== UI ====================

    CallUI(
        peerName = peerName,
        elapsedMillis = elapsedMillis,
        isMuted = isMuted,
        isSpeakerOn = isSpeakerOn,
        isLocalVideoEnabled = isLocalVideoEnabled,
        isRemoteVideoEnabled = isRemoteVideoEnabled,
        connectionState = connectionState,
        callQuality = callQuality,
        videoSwapped = videoSwapped,
        onToggleMic = { WebRtcCallManager.toggleMic() },
        onToggleSpeaker = { WebRtcCallManager.toggleSpeaker() },
        onToggleVideo = {
            Log.d("CallScreen", "📹 Toggle video")
            WebRtcCallManager.toggleVideo()
        },
        onSwitchCamera = { WebRtcCallManager.switchCamera() },
        onSwapVideo = { videoSwapped = !videoSwapped },
        onHangup = {
            callEnded = true
            scope.launch(Dispatchers.IO) {
                db.collection("calls").document(callId)
                    .update("endedAt", FieldValue.serverTimestamp())
            }
            WebRtcCallManager.endCall()
            CallService.stop(context)
            OngoingCallStore.clear(context)
            onNavigateBack()
        }
    )

    // Dialogs
    videoUpgradeRequest?.let { request ->
        VideoUpgradeDialog(
            fromUsername = request.fromUsername,
            onAccept = { WebRtcCallManager.acceptVideoUpgrade() },
            onDecline = { WebRtcCallManager.declineVideoUpgrade() }
        )
    }

    if (showEndCallDialog) {
        AlertDialog(
            onDismissRequest = { showEndCallDialog = false },
            title = { Text("Завершить звонок?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEndCallDialog = false
                        callEnded = true
                        scope.launch(Dispatchers.IO) {
                            db.collection("calls").document(callId)
                                .update("endedAt", FieldValue.serverTimestamp())
                        }
                        WebRtcCallManager.endCall()
                        CallService.stop(context)
                        OngoingCallStore.clear(context)
                        onNavigateBack()
                    }
                ) {
                    Text("Завершить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndCallDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//                            MAIN UI
// ═══════════════════════════════════════════════════════════════

@Composable
private fun CallUI(
    peerName: String,
    elapsedMillis: Long,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isLocalVideoEnabled: Boolean,
    isRemoteVideoEnabled: Boolean,
    connectionState: WebRtcCallManager.ConnectionState,
    callQuality: WebRtcCallManager.Quality,
    videoSwapped: Boolean,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onSwapVideo: () -> Unit,
    onHangup: () -> Unit
) {
    val showVideo = isLocalVideoEnabled || isRemoteVideoEnabled
    val isConnected = connectionState == WebRtcCallManager.ConnectionState.CONNECTED

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF667EEA),
                        Color(0xFF764BA2)
                    )
                )
            )
    ) {
        // Video or Audio
        if (showVideo) {
            VideoContent(
                isLocalVideoEnabled = isLocalVideoEnabled,
                isRemoteVideoEnabled = isRemoteVideoEnabled,
                videoSwapped = videoSwapped,
                peerName = peerName,
                onSwapVideo = onSwapVideo
            )
        } else {
            AudioContent(peerName = peerName, isConnected = isConnected)
        }

        // Top overlay
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(0.6f),
                            Color.Transparent
                        )
                    )
                )
                .padding(top = 50.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = peerName,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            val statusText = when (connectionState) {
                WebRtcCallManager.ConnectionState.CONNECTING -> "Соединение..."
                WebRtcCallManager.ConnectionState.CONNECTED -> formatTime(elapsedMillis)
                WebRtcCallManager.ConnectionState.RECONNECTING -> "Переподключение..."
                WebRtcCallManager.ConnectionState.FAILED -> "Ошибка"
                WebRtcCallManager.ConnectionState.DISCONNECTED -> "Отключено"
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connectionState != WebRtcCallManager.ConnectionState.CONNECTED) {
                    StatusChip(connectionState)
                }
                if (callQuality != WebRtcCallManager.Quality.Good && isConnected) {
                    QualityChip(callQuality)
                }
            }
        }

        // Bottom controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(0.7f)
                        )
                    )
                )
                .padding(bottom = 40.dp, top = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    isActive = !isMuted,
                    onClick = onToggleMic
                )

                ControlButton(
                    icon = if (isLocalVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    isActive = isLocalVideoEnabled,
                    onClick = onToggleVideo
                )

                if (isLocalVideoEnabled) {
                    ControlButton(
                        icon = Icons.Default.Cameraswitch,
                        isActive = true,
                        onClick = onSwitchCamera
                    )
                }

                ControlButton(
                    icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                    isActive = isSpeakerOn,
                    onClick = onToggleSpeaker
                )
            }

            Spacer(Modifier.height(24.dp))

            Surface(
                onClick = onHangup,
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                color = Color(0xFFFF3B30),
                shadowElevation = 12.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//                         VIDEO CONTENT
// ═══════════════════════════════════════════════════════════════

@Composable
private fun VideoContent(
    isLocalVideoEnabled: Boolean,
    isRemoteVideoEnabled: Boolean,
    videoSwapped: Boolean,
    peerName: String,
    onSwapVideo: () -> Unit
) {
    val showRemoteAsMain = !videoSwapped

    Box(modifier = Modifier.fillMaxSize()) {
        // Main video
        when {
            showRemoteAsMain && isRemoteVideoEnabled -> {
                RemoteVideoView(Modifier.fillMaxSize())
            }
            !showRemoteAsMain && isLocalVideoEnabled -> {
                LocalVideoView(Modifier.fillMaxSize())
            }
            else -> {
                VideoPlaceholder(peerName)
            }
        }

        // PIP video
        AnimatedVisibility(
            visible = (showRemoteAsMain && isLocalVideoEnabled) || (!showRemoteAsMain && isRemoteVideoEnabled),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .zIndex(10f)
        ) {
            Surface(
                modifier = Modifier
                    .size(100.dp, 140.dp)
                    .clickable(onClick = onSwapVideo),
                shape = RoundedCornerShape(16.dp),
                color = Color.Black,
                shadowElevation = 12.dp
            ) {
                if (showRemoteAsMain && isLocalVideoEnabled) {
                    LocalVideoView(Modifier.fillMaxSize())
                } else if (!showRemoteAsMain && isRemoteVideoEnabled) {
                    RemoteVideoView(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun LocalVideoView(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                WebRtcCallManager.prepareRenderer(this, mirror = true, overlay = false)
                post {
                    WebRtcCallManager.bindLocalRenderer(this)
                }
            }
        }
    )
}

@Composable
private fun RemoteVideoView(modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                WebRtcCallManager.prepareRenderer(this, mirror = false, overlay = false)
                post {
                    WebRtcCallManager.bindRemoteRenderer(this)
                }
            }
        }
    )
}

@Composable
private fun VideoPlaceholder(name: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2C2C3E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF667EEA),
                                Color(0xFF764BA2)
                            )
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Icon(
                Icons.Default.VideocamOff,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.White.copy(0.7f)
            )

            Text(
                "Камера выключена",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(0.7f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//                         AUDIO CONTENT
// ═══════════════════════════════════════════════════════════════

@Composable
private fun AudioContent(peerName: String, isConnected: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (isConnected) 1f else 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )

        Box(
            modifier = Modifier.size(160.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!isConnected) {
                repeat(3) { i ->
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, delayMillis = i * 700),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "alpha$i"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(1f + (i * 0.4f))
                            .background(
                                Color.White.copy(alpha * 0.3f),
                                CircleShape
                            )
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(140.dp)
                    .scale(scale)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(0.3f),
                                Color.White.copy(0.1f)
                            )
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peerName.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 60.sp
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//                          COMPONENTS
// ═══════════════════════════════════════════════════════════════

@Composable
private fun StatusChip(state: WebRtcCallManager.ConnectionState) {
    val (text, color) = when (state) {
        WebRtcCallManager.ConnectionState.CONNECTING -> "Соединение" to Color(0xFFFFA726)
        WebRtcCallManager.ConnectionState.RECONNECTING -> "Переподключение" to Color(0xFFFFA726)
        WebRtcCallManager.ConnectionState.FAILED -> "Ошибка" to Color(0xFFFF6B6B)
        WebRtcCallManager.ConnectionState.DISCONNECTED -> "Отключено" to Color(0xFF9E9E9E)
        else -> return
    }

    Surface(
        color = color.copy(0.25f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun QualityChip(quality: WebRtcCallManager.Quality) {
    val (text, color) = when (quality) {
        WebRtcCallManager.Quality.Medium -> "Среднее качество" to Color(0xFFFFA726)
        WebRtcCallManager.Quality.Poor -> "Плохое качество" to Color(0xFFFF6B6B)
        else -> return
    }

    Surface(
        color = color.copy(0.25f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isActive) {
        Color.White.copy(0.25f)
    } else {
        Color(0xFFFF3B30)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.size(60.dp),
        shape = CircleShape,
        color = backgroundColor,
        shadowElevation = 8.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun VideoUpgradeDialog(
    fromUsername: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    Dialog(
        onDismissRequest = onDecline,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF667EEA),
                                    Color(0xFF764BA2)
                                )
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = Color.White
                    )
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "$fromUsername хочет включить видео",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF1A1A1A),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Отклонить", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    }

                    Button(
                        onClick = onAccept,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF667EEA)
                        )
                    ) {
                        Text("Включить", fontWeight = FontWeight.Medium, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}