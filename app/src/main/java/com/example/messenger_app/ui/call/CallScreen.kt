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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * ПОЛНОСТЬЮ НОВЫЙ CallScreen
 * - Исправлено аудио
 * - Исправлено видео
 * - Swap видео
 * - Минималистичный дизайн
 * - Превью состояние
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

    Log.d("CallScreen", "=== CALL START === callId=$callId, isVideo=$isVideo, role=$role")

    // WebRTC состояния
    val isMuted by WebRtcCallManager.isMuted.collectAsState()
    val isSpeakerOn by WebRtcCallManager.isSpeakerOn.collectAsState()
    val isVideoEnabled by WebRtcCallManager.isVideoEnabled.collectAsState()
    val isRemoteVideoEnabled by WebRtcCallManager.isRemoteVideoEnabled.collectAsState()
    val connectionState by WebRtcCallManager.connectionState.collectAsState()
    val callQuality by WebRtcCallManager.callQuality.collectAsState()
    val videoUpgradeRequest by WebRtcCallManager.videoUpgradeRequest.collectAsState()

    // Локальное состояние
    var callEnded by remember { mutableStateOf(false) }
    var showEndCallDialog by remember { mutableStateOf(false) }
    var videoSwapped by remember { mutableStateOf(false) }

    // Убираем пуш
    LaunchedEffect(callId) {
        NotificationHelper.cancelIncomingCall(context, callId)
    }

    val db = remember { FirebaseFirestore.getInstance() }
    val repo = remember { CallsRepository(FirebaseAuth.getInstance(), db) }
    val scope = rememberCoroutineScope()

    // Инициализация WebRTC
    LaunchedEffect(callId, isVideo, role) {
        Log.d("CallScreen", "Initializing WebRTC...")

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

        Log.d("CallScreen", "WebRTC initialized")
    }

    // SignalingDelegate
    DisposableEffect(callId, role) {
        WebRtcCallManager.signalingDelegate = object : WebRtcCallManager.SignalingDelegate {
            override fun onLocalDescription(callId: String, sdp: org.webrtc.SessionDescription) {
                scope.launch(Dispatchers.IO) {
                    if (role == "caller") {
                        repo.setOffer(callId, sdp.description, "offer")
                    } else {
                        repo.setAnswer(callId, sdp.description, "answer")
                    }
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
    var startedAtMillis by remember { mutableStateOf<Long?>(null) }
    var ended by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startedAtMillis, ended) {
        while (startedAtMillis != null && !ended) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    val elapsedMillis = if (startedAtMillis != null && !ended) {
        (nowMs - startedAtMillis!!).coerceAtLeast(0)
    } else 0L

    var peerName by remember { mutableStateOf(otherUsername) }
    var offerApplied by remember { mutableStateOf(false) }
    var answerApplied by remember { mutableStateOf(false) }

    // Firestore listeners
    DisposableEffect(callId, role) {
        val callDoc = db.collection("calls").document(callId)

        val docReg: ListenerRegistration = callDoc.addSnapshotListener { snap, error ->
            if (error != null) {
                Log.e("CallScreen", "Firestore error", error)
                return@addSnapshotListener
            }

            val data = snap?.data ?: return@addSnapshotListener

            // Получаем имя собеседника
            if (peerName.isNullOrBlank()) {
                peerName = when (role) {
                    "caller" -> (data["calleeUsername"] ?: data["toUsername"]) as? String
                    else -> (data["callerUsername"] ?: data["fromUsername"]) as? String
                }
            }

            // Обрабатываем SDP
            val offer = (data["offer"] as? Map<*, *>)?.get("sdp") as? String
            val answer = (data["answer"] as? Map<*, *>)?.get("sdp") as? String

            if (role == "callee" && !offer.isNullOrBlank() && !offerApplied) {
                Log.d("CallScreen", "Applying remote offer")
                offerApplied = true
                WebRtcCallManager.applyRemoteOffer(offer)
            }

            if (role == "caller" && !answer.isNullOrBlank() && !answerApplied) {
                Log.d("CallScreen", "Applying remote answer")
                answerApplied = true
                WebRtcCallManager.applyRemoteAnswer(answer)
                CallService.stopRingback(context)
            }

            // Обрабатываем startedAt
            val ts = data["startedAt"]
            if (ts is com.google.firebase.Timestamp) {
                if (startedAtMillis == null) {
                    startedAtMillis = ts.toDate().time
                    CallService.stopRingback(context)
                    Log.d("CallScreen", "Call started at: ${ts.toDate()}")
                }
            } else {
                val hasAnswer = (data["answer"] as? Map<*, *>)?.get("sdp") != null
                if (hasAnswer && startedAtMillis == null) {
                    scope.launch(Dispatchers.IO) {
                        db.runTransaction { tx ->
                            val snap2 = tx.get(callDoc)
                            if (snap2.get("startedAt") == null) {
                                tx.update(callDoc, "startedAt", FieldValue.serverTimestamp())
                            }
                        }
                    }
                    CallService.stopRingback(context)
                }
            }

            // Video upgrade request
            val videoUpgradeTs = data["videoUpgradeRequest"]
            if (videoUpgradeTs != null && role == "callee") {
                val fromUser = (data["callerUsername"] ?: data["fromUsername"] ?: "Собеседник") as String
                WebRtcCallManager.onRemoteVideoUpgradeRequest(fromUser)
            }

            // Завершение звонка
            if (data["endedAt"] != null && !ended) {
                Log.d("CallScreen", "Call ended")
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

        // ICE candidates
        val iceColl = if (role == "caller") "calleeCandidates" else "callerCandidates"
        val iceReg: ListenerRegistration = callDoc.collection(iceColl)
            .addSnapshotListener { snap, error ->
                if (error != null) {
                    Log.e("CallScreen", "ICE error", error)
                    return@addSnapshotListener
                }

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
    MinimalistCallUI(
        peerName = peerName ?: "Соединение...",
        elapsedMillis = elapsedMillis,
        isMuted = isMuted,
        isSpeakerOn = isSpeakerOn,
        isVideoEnabled = isVideoEnabled,
        isRemoteVideoEnabled = isRemoteVideoEnabled,
        connectionState = connectionState,
        callQuality = callQuality,
        videoSwapped = videoSwapped,
        onToggleMic = { WebRtcCallManager.toggleMic() },
        onToggleSpeaker = { WebRtcCallManager.toggleSpeaker() },
        onToggleVideo = { WebRtcCallManager.toggleVideo() },
        onSwitchCamera = { WebRtcCallManager.switchCamera() },
        onSwapVideo = { videoSwapped = !videoSwapped },
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

    // Диалог video upgrade
    videoUpgradeRequest?.let { request ->
        VideoUpgradeDialog(
            fromUsername = request.fromUsername,
            onAccept = { WebRtcCallManager.acceptVideoUpgrade() },
            onDecline = { WebRtcCallManager.declineVideoUpgrade() }
        )
    }

    // Диалог завершения
    if (showEndCallDialog) {
        AlertDialog(
            onDismissRequest = { showEndCallDialog = false },
            title = { Text("Завершить звонок?") },
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

// ==================== МИНИМАЛИСТИЧНЫЙ UI ====================

@Composable
private fun MinimalistCallUI(
    peerName: String,
    elapsedMillis: Long,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isVideoEnabled: Boolean,
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
    val context = LocalContext.current
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            localRenderer?.release()
            remoteRenderer?.release()
        }
    }

    val showVideo = isVideoEnabled || isRemoteVideoEnabled
    val isConnected = connectionState == WebRtcCallManager.ConnectionState.CONNECTED

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1C1C1E))
    ) {
        if (showVideo) {
            // ========== ВИДЕО РЕЖИМ ==========

            // Главное видео (большое)
            val showRemoteAsMain = !videoSwapped

            if (showRemoteAsMain && isRemoteVideoEnabled) {
                // Remote на весь экран
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            WebRtcCallManager.prepareRenderer(this, mirror = false, overlay = false)
                            WebRtcCallManager.bindRemoteRenderer(this)
                            remoteRenderer = this
                        }
                    }
                )
            } else if (!showRemoteAsMain && isVideoEnabled) {
                // Local на весь экран
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            WebRtcCallManager.prepareRenderer(this, mirror = true, overlay = false)
                            WebRtcCallManager.bindLocalRenderer(this)
                            localRenderer = this
                        }
                    }
                )
            } else {
                // Placeholder
                VideoPlaceholder(peerName)
            }

            // Маленькое видео (PiP) - кликабельное для swap
            AnimatedVisibility(
                visible = isVideoEnabled || isRemoteVideoEnabled,
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
                    shadowElevation = 8.dp
                ) {
                    if (showRemoteAsMain && isVideoEnabled) {
                        // Показываем local
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                SurfaceViewRenderer(ctx).apply {
                                    WebRtcCallManager.prepareRenderer(this, mirror = true, overlay = true)
                                    WebRtcCallManager.bindLocalRenderer(this)
                                }
                            }
                        )
                    } else if (!showRemoteAsMain && isRemoteVideoEnabled) {
                        // Показываем remote
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                SurfaceViewRenderer(ctx).apply {
                                    WebRtcCallManager.prepareRenderer(this, mirror = false, overlay = true)
                                    WebRtcCallManager.bindRemoteRenderer(this)
                                }
                            }
                        )
                    }
                }
            }
        } else {
            // ========== АУДИО РЕЖИМ ==========
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Spacer(Modifier.weight(1f))

                // Аватар
                AnimatedAvatar(peerName, isConnected)

                Spacer(Modifier.height(32.dp))

                // Имя
                Text(
                    text = peerName,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(Modifier.height(8.dp))

                // Статус
                StatusText(connectionState, elapsedMillis)

                Spacer(Modifier.weight(1f))
            }
        }

        // Градиент сверху для индикаторов
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.6f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Индикаторы вверху
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (connectionState != WebRtcCallManager.ConnectionState.CONNECTED) {
                StatusChip(connectionState)
            }
            if (callQuality != WebRtcCallManager.Quality.Good && isConnected) {
                QualityChip(callQuality)
            }
        }

        // Градиент снизу для контролов
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
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

        // Контролы внизу
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Микрофон
                ControlButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    isActive = !isMuted,
                    color = if (isMuted) Color(0xFFFF3B30) else Color.White.copy(0.3f),
                    onClick = onToggleMic
                )

                // Видео
                ControlButton(
                    icon = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    isActive = isVideoEnabled,
                    color = if (isVideoEnabled) Color.White.copy(0.3f) else Color.White.copy(0.15f),
                    onClick = onToggleVideo
                )

                // Переключение камеры (только если видео включено)
                if (isVideoEnabled) {
                    ControlButton(
                        icon = Icons.Default.Cameraswitch,
                        isActive = true,
                        color = Color.White.copy(0.3f),
                        onClick = onSwitchCamera
                    )
                }

                // Громкость
                ControlButton(
                    icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                    isActive = isSpeakerOn,
                    color = Color.White.copy(0.3f),
                    onClick = onToggleSpeaker
                )
            }

            Spacer(Modifier.height(24.dp))

            // Кнопка завершения
            HangupButton(onClick = onHangup)
        }
    }
}

// ==================== КОМПОНЕНТЫ ====================

@Composable
private fun VideoPlaceholder(name: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2C2C2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .background(Color.White.copy(0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.displayMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(16.dp))

            Icon(
                Icons.Default.VideocamOff,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White.copy(0.6f)
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Камера выключена",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(0.6f)
            )
        }
    }
}

@Composable
private fun AnimatedAvatar(name: String, isConnected: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "avatar")

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
        modifier = Modifier.size(140.dp),
        contentAlignment = Alignment.Center
    ) {
        // Пульсация только если не соединено
        if (!isConnected) {
            repeat(2) { i ->
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.5f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1500, delayMillis = i * 750),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "alpha$i"
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(1f + (i * 0.3f))
                        .background(Color.White.copy(alpha * 0.3f), CircleShape)
                )
            }
        }

        // Аватар
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .background(Color.White.copy(0.1f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatusText(state: WebRtcCallManager.ConnectionState, elapsedMillis: Long) {
    val text = when (state) {
        WebRtcCallManager.ConnectionState.CONNECTING -> "Соединение..."
        WebRtcCallManager.ConnectionState.CONNECTED -> formatElapsed(elapsedMillis)
        WebRtcCallManager.ConnectionState.RECONNECTING -> "Переподключение..."
        WebRtcCallManager.ConnectionState.FAILED -> "Не удалось соединиться"
        WebRtcCallManager.ConnectionState.DISCONNECTED -> "Отключено"
    }

    val color = when (state) {
        WebRtcCallManager.ConnectionState.CONNECTED -> Color.White.copy(0.8f)
        WebRtcCallManager.ConnectionState.FAILED -> Color(0xFFFF3B30)
        else -> Color.White.copy(0.6f)
    }

    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = color
    )
}

@Composable
private fun StatusChip(state: WebRtcCallManager.ConnectionState) {
    val (text, color) = when (state) {
        WebRtcCallManager.ConnectionState.CONNECTING -> "Соединение" to Color(0xFFFF9500)
        WebRtcCallManager.ConnectionState.RECONNECTING -> "Переподключение" to Color(0xFFFF9500)
        WebRtcCallManager.ConnectionState.FAILED -> "Ошибка" to Color(0xFFFF3B30)
        WebRtcCallManager.ConnectionState.DISCONNECTED -> "Отключено" to Color(0xFF8E8E93)
        else -> return
    }

    Surface(
        color = color.copy(0.9f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun QualityChip(quality: WebRtcCallManager.Quality) {
    if (quality == WebRtcCallManager.Quality.Good) return

    val (text, color) = when (quality) {
        WebRtcCallManager.Quality.Medium -> "Среднее качество" to Color(0xFFFF9500)
        WebRtcCallManager.Quality.Poor -> "Плохое качество" to Color(0xFFFF3B30)
        else -> return
    }

    Surface(
        color = color.copy(0.9f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(56.dp),
        shape = CircleShape,
        color = color
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun HangupButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        shape = CircleShape,
        color = Color(0xFFFF3B30),
        shadowElevation = 8.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.CallEnd,
                contentDescription = "Завершить",
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
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF2C2C2E),
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color(0xFF007AFF)
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "$fromUsername хочет включить видео",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White
                        )
                    ) {
                        Text("Отклонить", Modifier.padding(vertical = 4.dp))
                    }

                    Button(
                        onClick = onAccept,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF007AFF)
                        )
                    ) {
                        Text("Включить видео", Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}