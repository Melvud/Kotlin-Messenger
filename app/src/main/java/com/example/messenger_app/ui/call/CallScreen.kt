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
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

/**
 * УЛУЧШЕННЫЙ CallScreen с современным красивым дизайном
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
    val role = remember(playRingback) { if (playRingback) "caller" else "callee" }

    Log.d("CallScreen", "Initialized: callId=$callId, isVideo=$isVideo, role=$role")

    // WebRTC состояния
    val isMuted by WebRtcCallManager.isMuted.collectAsState()
    val isSpeakerOn by WebRtcCallManager.isSpeakerOn.collectAsState()
    val isVideoEnabled by WebRtcCallManager.isVideoEnabled.collectAsState()
    val connectionState by WebRtcCallManager.connectionState.collectAsState()
    val callQuality by WebRtcCallManager.callQuality.collectAsState()
    val videoUpgradeRequest by WebRtcCallManager.videoUpgradeRequest.collectAsState()

    // Локальное состояние
    var callEnded by remember { mutableStateOf(false) }
    var showEndCallDialog by remember { mutableStateOf(false) }

    // Убираем входящий пуш
    LaunchedEffect(callId) {
        NotificationHelper.cancelIncomingCall(context, callId)
    }

    val db = remember { FirebaseFirestore.getInstance() }
    val repo = remember { CallsRepository(FirebaseAuth.getInstance(), db) }
    val scope = rememberCoroutineScope()

    // Инициализация WebRTC
    LaunchedEffect(callId, isVideo, role, playRingback) {
        Log.d("CallScreen", "Starting WebRTC: callId=$callId, isVideo=$isVideo, role=$role")

        WebRtcCallManager.init(context)

        val shouldPlayRingback = (role == "caller" && playRingback)

        WebRtcCallManager.startCall(
            callId = callId,
            isVideo = isVideo,
            playRingback = shouldPlayRingback,
            role = role
        )

        CallService.start(
            ctx = context,
            callId = callId,
            username = otherUsername.orEmpty(),
            isVideo = isVideo,
            openUi = false,
            playRingback = shouldPlayRingback
        )

        Log.d("CallScreen", "WebRTC started successfully")
    }

    // SignalingDelegate
    DisposableEffect(callId, role) {
        WebRtcCallManager.signalingDelegate = object : WebRtcCallManager.SignalingDelegate {
            override fun onLocalDescription(callId: String, sdp: org.webrtc.SessionDescription) {
                scope.launch(Dispatchers.IO) {
                    if (role == "caller") repo.setOffer(callId, sdp.description, "offer")
                    else repo.setAnswer(callId, sdp.description, "answer")
                }
            }

            override fun onIceCandidate(callId: String, candidate: org.webrtc.IceCandidate) {
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
        onDispose { WebRtcCallManager.signalingDelegate = null }
    }

    // Таймер
    var startedAtMillis by remember(callId) { mutableStateOf<Long?>(null) }
    var ended by remember(callId) { mutableStateOf(false) }
    var nowMs by remember(callId) { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startedAtMillis, ended) {
        while (startedAtMillis != null && !ended) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    val elapsedMillis = if (startedAtMillis != null && !ended) {
        (nowMs - startedAtMillis!!).coerceAtLeast(0)
    } else 0L

    var peerName by remember(callId) { mutableStateOf(otherUsername) }
    var offerApplied by remember(callId) { mutableStateOf(false) }
    var answerApplied by remember(callId) { mutableStateOf(false) }

    // Firestore listeners
    DisposableEffect(callId, role) {
        val callDoc = db.collection("calls").document(callId)
        val docReg: ListenerRegistration = callDoc.addSnapshotListener { snap, _ ->
            val data = snap?.data ?: return@addSnapshotListener

            if (peerName.isNullOrBlank()) {
                peerName = when (role) {
                    "caller" -> (data["calleeUsername"] ?: data["toUsername"]) as? String
                    else -> (data["callerUsername"] ?: data["fromUsername"]) as? String
                }
            }

            val offer = (data["offer"] as? Map<*, *>)?.get("sdp") as? String
            val answer = (data["answer"] as? Map<*, *>)?.get("sdp") as? String

            if (role == "callee" && !offer.isNullOrBlank() && !offerApplied) {
                offerApplied = true
                WebRtcCallManager.applyRemoteOffer(offer)
            }
            if (role == "caller" && !answer.isNullOrBlank() && !answerApplied) {
                answerApplied = true
                WebRtcCallManager.applyRemoteAnswer(answer)
                CallService.stopRingback(context)
            }

            val ts = data["startedAt"]
            if (ts is com.google.firebase.Timestamp) {
                if (startedAtMillis == null) {
                    startedAtMillis = ts.toDate().time
                    CallService.stopRingback(context)
                }
            } else {
                val hasAnswer = (data["answer"] as? Map<*, *>)?.get("sdp") != null
                if (hasAnswer && startedAtMillis == null) {
                    db.runTransaction { tx ->
                        val snap2 = tx.get(callDoc)
                        if (snap2.get("startedAt") == null) {
                            tx.update(callDoc, "startedAt", FieldValue.serverTimestamp())
                        }
                    }
                    CallService.stopRingback(context)
                }
            }

            val videoUpgradeTs = data["videoUpgradeRequest"]
            if (videoUpgradeTs != null && role == "callee") {
                val fromUser = (data["callerUsername"] ?: data["fromUsername"] ?: "Собеседник") as String
                WebRtcCallManager.onRemoteVideoUpgradeRequest(fromUser)
            }

            if (data["endedAt"] != null && !ended) {
                ended = true
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
        val iceReg: ListenerRegistration = callDoc.collection(iceColl)
            .addSnapshotListener { snap, _ ->
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

    // Broadcast receiver
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    CallActionReceiver.ACTION_INTERNAL_HANGUP -> {
                        ended = true
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
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Cleanup
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

    // BackHandler
    BackHandler {
        showEndCallDialog = true
    }

    // UI
    if (isVideoEnabled) {
        ModernVideoCallUI(
            peerName = peerName ?: "Звонок",
            elapsedMillis = elapsedMillis,
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            isVideoEnabled = isVideoEnabled,
            connectionState = connectionState,
            callQuality = callQuality,
            onToggleMic = { WebRtcCallManager.toggleMic() },
            onToggleSpeaker = { WebRtcCallManager.toggleSpeaker() },
            onToggleVideo = { WebRtcCallManager.toggleVideo() },
            onSwitchCamera = { WebRtcCallManager.switchCamera() },
            onHangup = {
                ended = true
                scope.launch(Dispatchers.IO) {
                    db.collection("calls").document(callId).update("endedAt", FieldValue.serverTimestamp())
                }
                WebRtcCallManager.endCall()
                CallService.stop(context)
                OngoingCallStore.clear(context)
                onNavigateBack()
            }
        )
    } else {
        ModernAudioCallUI(
            peerName = peerName ?: "Звонок",
            elapsedMillis = elapsedMillis,
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            connectionState = connectionState,
            callQuality = callQuality,
            onToggleMic = { WebRtcCallManager.toggleMic() },
            onToggleSpeaker = { WebRtcCallManager.toggleSpeaker() },
            onEnableVideo = { WebRtcCallManager.requestVideoUpgrade() },
            onHangup = {
                ended = true
                scope.launch(Dispatchers.IO) {
                    db.collection("calls").document(callId).update("endedAt", FieldValue.serverTimestamp())
                }
                WebRtcCallManager.endCall()
                CallService.stop(context)
                OngoingCallStore.clear(context)
                onNavigateBack()
            }
        )
    }

    // Диалог запроса видео
    videoUpgradeRequest?.let { request ->
        ModernVideoUpgradeDialog(
            fromUsername = request.fromUsername,
            onAccept = {
                WebRtcCallManager.acceptVideoUpgrade()
            },
            onDecline = {
                WebRtcCallManager.declineVideoUpgrade()
            }
        )
    }

    // Диалог подтверждения завершения
    if (showEndCallDialog) {
        AlertDialog(
            onDismissRequest = { showEndCallDialog = false },
            title = { Text("Завершить звонок?") },
            text = { Text("Вы уверены, что хотите завершить этот звонок?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEndCallDialog = false
                        ended = true
                        scope.launch(Dispatchers.IO) {
                            db.collection("calls").document(callId).update("endedAt", FieldValue.serverTimestamp())
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

// ==================== СОВРЕМЕННЫЙ АУДИО ЗВОНОК UI ====================

@Composable
private fun ModernAudioCallUI(
    peerName: String,
    elapsedMillis: Long,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    connectionState: WebRtcCallManager.ConnectionState,
    callQuality: WebRtcCallManager.Quality,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEnableVideo: () -> Unit,
    onHangup: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF667eea),
                        Color(0xFF764ba2),
                        Color(0xFF8e44ad)
                    )
                )
            )
    ) {
        // Фоновый декоративный круг
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-100).dp)
                .size(400.dp)
                .alpha(0.1f)
                .background(Color.White, CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))

            // Индикаторы состояния
            ConnectionAndQualityIndicators(connectionState, callQuality)

            Spacer(Modifier.weight(0.3f))

            // Пульсирующий аватар
            AnimatedCallAvatar(peerName)

            Spacer(Modifier.height(32.dp))

            // Имя
            Text(
                text = peerName,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            // Таймер
            Text(
                text = formatElapsed(elapsedMillis),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White.copy(alpha = 0.8f),
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.weight(1f))

            // Контролы
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModernControlButton(
                    icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    isActive = !isMuted,
                    onClick = onToggleMic,
                    label = if (isMuted) "Включить" else "Выкл"
                )

                ModernControlButton(
                    icon = if (isSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                    isActive = isSpeakerOn,
                    onClick = onToggleSpeaker,
                    label = "Громкость"
                )

                ModernControlButton(
                    icon = Icons.Filled.Videocam,
                    isActive = false,
                    onClick = onEnableVideo,
                    label = "Видео"
                )
            }

            Spacer(Modifier.height(48.dp))

            // Кнопка завершения
            ModernHangupButton(onClick = onHangup)

            Spacer(Modifier.height(48.dp))
        }
    }
}

// ==================== СОВРЕМЕННЫЙ ВИДЕО ЗВОНОК UI ====================

@Composable
private fun ModernVideoCallUI(
    peerName: String,
    elapsedMillis: Long,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isVideoEnabled: Boolean,
    connectionState: WebRtcCallManager.ConnectionState,
    callQuality: WebRtcCallManager.Quality,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onHangup: () -> Unit
) {
    val context = LocalContext.current
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            localRenderer?.release()
            remoteRenderer?.release()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Remote video (полный экран с blur эффектом для фона)
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(40.dp)
                    .alpha(0.5f),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        val renderer = this
                        WebRtcCallManager.prepareRenderer(renderer, mirror = false, overlay = false)
                        WebRtcCallManager.bindRemoteRenderer(renderer)
                        remoteRenderer = renderer
                    }
                }
            )

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        val renderer = this
                        WebRtcCallManager.prepareRenderer(renderer, mirror = false, overlay = false)
                        WebRtcCallManager.bindRemoteRenderer(renderer)
                    }
                }
            )
        }

        // Local video (PiP с красивой рамкой)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp)
                .size(120.dp, 180.dp)
                .zIndex(10f)
                .border(
                    width = 3.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f),
                            Color.White.copy(alpha = 0.1f)
                        )
                    ),
                    shape = RoundedCornerShape(20.dp)
                )
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.3f))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        val renderer = this
                        WebRtcCallManager.prepareRenderer(renderer, mirror = true, overlay = true)
                        WebRtcCallManager.bindLocalRenderer(renderer)
                        localRenderer = renderer
                    }
                }
            )
        }

        // Градиентный оверлей сверху
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.7f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Градиентный оверлей снизу
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
        )

        // Индикаторы и информация
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectionAndQualityIndicators(connectionState, callQuality)

            GlassInfoChip(peerName)
            GlassInfoChip(formatElapsed(elapsedMillis))
        }

        // Контролы
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModernVideoControlButton(
                    icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    isActive = !isMuted,
                    onClick = onToggleMic
                )

                ModernVideoControlButton(
                    icon = if (isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    isActive = isVideoEnabled,
                    onClick = onToggleVideo
                )

                ModernVideoControlButton(
                    icon = Icons.Filled.Cameraswitch,
                    isActive = true,
                    onClick = onSwitchCamera
                )
            }

            Spacer(Modifier.height(32.dp))

            ModernHangupButton(onClick = onHangup)
        }
    }
}

// ==================== UI КОМПОНЕНТЫ ====================

@Composable
private fun AnimatedCallAvatar(name: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Box(contentAlignment = Alignment.Center) {
        // Пульсирующие круги
        repeat(3) { index ->
            val delay = index * 500
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = LinearEasing, delayMillis = delay),
                    repeatMode = RepeatMode.Restart
                ),
                label = "alpha$index"
            )
            val circleScale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = FastOutSlowInEasing, delayMillis = delay),
                    repeatMode = RepeatMode.Restart
                ),
                label = "circleScale$index"
            )

            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(circleScale)
                    .alpha(alpha)
                    .background(
                        Color.White.copy(alpha = 0.3f),
                        CircleShape
                    )
            )
        }

        // Основной аватар
        Box(
            modifier = Modifier
                .size(150.dp)
                .scale(scale)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.9f),
                            Color.White.copy(alpha = 0.7f)
                        )
                    ),
                    CircleShape
                )
                .border(4.dp, Color.White.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                style = MaterialTheme.typography.displayLarge,
                color = Color(0xFF667eea),
                fontWeight = FontWeight.Bold,
                fontSize = 56.sp
            )
        }
    }
}

@Composable
private fun ModernControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    label: String? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = if (isActive)
                Color.White.copy(alpha = 0.25f)
            else
                Color.White.copy(alpha = 0.15f),
            shadowElevation = if (isActive) 8.dp else 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
            }
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ModernVideoControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        shape = CircleShape,
        color = if (isActive)
            Color.White.copy(alpha = 0.25f)
        else
            Color.White.copy(alpha = 0.15f)
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
private fun ModernHangupButton(onClick: () -> Unit) {
    val scale by rememberInfiniteTransition(label = "hangup").animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(72.dp)
            .scale(scale),
        shape = CircleShape,
        color = Color(0xFFE53935),
        shadowElevation = 16.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.CallEnd,
                contentDescription = "Завершить",
                modifier = Modifier.size(36.dp),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun ConnectionAndQualityIndicators(
    connectionState: WebRtcCallManager.ConnectionState,
    quality: WebRtcCallManager.Quality
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConnectionIndicator(connectionState)
        if (connectionState == WebRtcCallManager.ConnectionState.CONNECTED) {
            QualityIndicator(quality)
        }
    }
}

@Composable
private fun ConnectionIndicator(state: WebRtcCallManager.ConnectionState) {
    val (text, color) = when (state) {
        WebRtcCallManager.ConnectionState.CONNECTING -> "Подключение..." to Color(0xFFFFA726)
        WebRtcCallManager.ConnectionState.RECONNECTING -> "Переподключение..." to Color(0xFFFF7043)
        WebRtcCallManager.ConnectionState.FAILED -> "Потеряно" to Color(0xFFE53935)
        WebRtcCallManager.ConnectionState.DISCONNECTED -> "Отключено" to Color(0xFF757575)
        else -> return
    }

    GlassChip(text = text, color = color)
}

@Composable
private fun QualityIndicator(quality: WebRtcCallManager.Quality) {
    if (quality == WebRtcCallManager.Quality.Good) return

    val (text, color) = when (quality) {
        WebRtcCallManager.Quality.Medium -> "Среднее" to Color(0xFFFFA726)
        WebRtcCallManager.Quality.Poor -> "Плохое" to Color(0xFFE53935)
        else -> return
    }

    GlassChip(text = text, color = color)
}

@Composable
private fun GlassChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.9f),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color.White, CircleShape)
            )
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun GlassInfoChip(text: String) {
    Surface(
        color = Color.White.copy(alpha = 0.2f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun ModernVideoUpgradeDialog(
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
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Анимированная иконка
                val scale by rememberInfiniteTransition(label = "icon").animateFloat(
                    initialValue = 1f,
                    targetValue = 1.1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "scale"
                )

                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .scale(scale)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "Переход на видео",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "$fromUsername хочет включить видео",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Нет, спасибо", Modifier.padding(vertical = 4.dp))
                    }

                    Button(
                        onClick = onAccept,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Включить камеру", Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

// ==================== HELPERS ====================

private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}