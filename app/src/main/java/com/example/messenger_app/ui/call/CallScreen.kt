package com.example.messenger_app.ui.call

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.example.messenger_app.data.CallsRepository
import com.example.messenger_app.push.CallActionReceiver
import com.example.messenger_app.push.CallService
import com.example.messenger_app.push.NotificationHelper
import com.example.messenger_app.push.OngoingCallStore
import com.example.messenger_app.ui.theme.AppTheme
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
 * УЛУЧШЕННЫЙ CallScreen с:
 * - Современным Material Design 3 UI
 * - Автоматическим переподключением
 * - Индикаторами состояния и качества
 * - Диалогом перехода на видео
 * - Анимациями и эффектами
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

    // УДАЛЕНО: Дублирующий вызов CallService
    // LaunchedEffect(callId, isVideo, otherUsername, playRingback) { ... }

    val db = remember { FirebaseFirestore.getInstance() }
    val repo = remember { CallsRepository(FirebaseAuth.getInstance(), db) }
    val scope = rememberCoroutineScope()

    // --- ИСПРАВЛЕНИЕ: ИНИЦИАЛИЗАЦИЯ WebRTC ПЕРЕМЕЩЕНА СЮДА ---
    // Это гарантирует, что `startCall` вызовется *ДО* того,
    // как `DisposableEffect` (Firestore listener) попытается вызвать `applyRemoteOffer`.
    LaunchedEffect(callId, isVideo, role) {
        WebRtcCallManager.init(context)

        // playRingback должен быть true только для вызывающего (caller).
        val shouldPlayRingback = role == "caller"

        WebRtcCallManager.startCall(
            callId = callId,
            isVideo = isVideo,
            playRingback = shouldPlayRingback,
            role = role
        )

        // Запускаем foreground сервис.
        // ИСПРАВЛЕНИЕ: Убран параметр `initializeConnection`
        CallService.start(
            ctx = context,
            callId = callId,
            username = otherUsername.orEmpty(),
            isVideo = isVideo,
            openUi = false,
            playRingback = shouldPlayRingback
        )
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
    // Теперь этот Effect будет создан *после* того, как LaunchedEffect выше
    // вызовет `startCall`, поэтому `peer` в WebRtcCallManager уже будет создан.
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

            // Video upgrade request
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

    // Broadcast receiver для внутренних команд
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

    // --- ИНИЦИАЛИЗАЦИЯ WebRTC БЫЛА ПЕРЕМЕЩЕНА ВВЕРХ ---
    // (блок LaunchedEffect был здесь, теперь он выше)


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
        VideoCallUI(
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
        AudioCallUI(
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
        VideoUpgradeDialog(
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

// ==================== AUDIO CALL UI ====================
// ... (остальной код UI без изменений) ...
@Composable
private fun AudioCallUI(
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
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.2f))

            // Индикаторы состояния
            ConnectionAndQualityIndicators(connectionState, callQuality)

            Spacer(Modifier.height(32.dp))

            // Аватар с пульсацией
            PulsatingAvatar(peerName)

            Spacer(Modifier.height(24.dp))

            // Имя
            Text(
                text = peerName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(Modifier.height(8.dp))

            // Таймер
            Text(
                text = formatElapsed(elapsedMillis),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )

            Spacer(Modifier.weight(1f))

            // Контролы
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Микрофон
                ControlButton(
                    icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    isActive = !isMuted,
                    onClick = onToggleMic,
                    label = if (isMuted) "Включить" else "Выкл"
                )

                // Спикер
                ControlButton(
                    icon = if (isSpeakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                    isActive = isSpeakerOn,
                    onClick = onToggleSpeaker,
                    label = "Громкость"
                )

                // Видео
                ControlButton(
                    icon = Icons.Filled.Videocam,
                    isActive = false,
                    onClick = onEnableVideo,
                    label = "Видео"
                )
            }

            Spacer(Modifier.height(32.dp))

            // Кнопка завершения
            FloatingActionButton(
                onClick = onHangup,
                containerColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    Icons.Filled.CallEnd,
                    contentDescription = "Завершить",
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ==================== VIDEO CALL UI ====================

@Composable
private fun VideoCallUI(
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
    var localPrimary by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            localRenderer?.release()
            remoteRenderer?.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Remote video (фон)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    remoteRenderer = this
                    WebRtcCallManager.prepareRenderer(this, mirror = false, overlay = false)
                    WebRtcCallManager.bindRemoteRenderer(this)
                }
            }
        )

        // Local video (PiP)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(120.dp, 160.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable { localPrimary = !localPrimary }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        localRenderer = this
                        WebRtcCallManager.prepareRenderer(this, mirror = true, overlay = true)
                        WebRtcCallManager.bindLocalRenderer(this)
                    }
                }
            )
        }

        // Индикаторы
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
        ) {
            ConnectionAndQualityIndicators(connectionState, callQuality)
            Spacer(Modifier.height(8.dp))
            InfoChip(peerName)
            Spacer(Modifier.height(4.dp))
            InfoChip(formatElapsed(elapsedMillis))
        }

        // Контролы
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ControlButton(
                    icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    isActive = !isMuted,
                    onClick = onToggleMic
                )

                ControlButton(
                    icon = if (isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    isActive = isVideoEnabled,
                    onClick = onToggleVideo
                )

                ControlButton(
                    icon = Icons.Filled.Cameraswitch,
                    isActive = true,
                    onClick = onSwitchCamera
                )
            }

            Spacer(Modifier.height(24.dp))

            FloatingActionButton(
                onClick = onHangup,
                containerColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    Icons.Filled.CallEnd,
                    contentDescription = "Завершить",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

// ==================== UI COMPONENTS ====================

@Composable
private fun PulsatingAvatar(name: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    onClick: () -> Unit,
    label: String? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(64.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = if (isActive)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = if (isActive)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConnectionIndicator(connectionState)
        QualityIndicator(quality)
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

    Surface(
        color = color,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.White)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun QualityIndicator(quality: WebRtcCallManager.Quality) {
    if (quality == WebRtcCallManager.Quality.Good) return

    val (text, color) = when (quality) {
        WebRtcCallManager.Quality.Medium -> "Среднее" to Color(0xFFFFA726)
        WebRtcCallManager.Quality.Poor -> "Плохое" to Color(0xFFE53935)
        else -> return
    }

    Surface(
        color = color,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        color = Color.Black.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
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
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Переход на видео",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "$fromUsername хочет включить видео.\nВключить камеру?",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Нет, спасибо")
                    }

                    Button(
                        onClick = onAccept,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Включить")
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

// ==================== PREVIEWS ====================

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun PreviewAudioCall() {
    AppTheme {
        AudioCallUI(
            peerName = "Джон Доу",
            elapsedMillis = 65000,
            isMuted = false,
            isSpeakerOn = true,
            connectionState = WebRtcCallManager.ConnectionState.CONNECTED,
            callQuality = WebRtcCallManager.Quality.Good,
            onToggleMic = {},
            onToggleSpeaker = {},
            onEnableVideo = {},
            onHangup = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewVideoUpgradeDialog() {
    AppTheme {
        VideoUpgradeDialog(
            fromUsername = "Мария",
            onAccept = {},
            onDecline = {}
        )
    }
}