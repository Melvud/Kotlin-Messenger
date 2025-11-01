package com.example.messenger_app.ui.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
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
 *         ПОЛНОСТЬЮ РАБОЧИЙ CALL SCREEN С КРАСИВЫМ ДИЗАЙНОМ
 * ═══════════════════════════════════════════════════════════════
 * ✅ Единый экран для аудио и видео
 * ✅ Видео отображается обоим участникам
 * ✅ Красивый современный дизайн
 * ✅ Правильная обработка всех состояний
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

        NotificationHelper.cancelIncomingCall(context, callId)
        WebRtcCallManager.init(context)

        WebRtcCallManager.startCall(
            callId = callId,
            isVideo = isVideo,
            playRingback = playRingback,
            role = role
        )

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
    var lastProcessedOfferTime by remember { mutableStateOf<Long?>(null) }
    // ==================== FIRESTORE ====================

    DisposableEffect(Unit) {
        val callDoc = db.collection("calls").document(callId)

        val docReg = callDoc.addSnapshotListener { snap, error ->
            if (error != null) return@addSnapshotListener
            val data = snap?.data ?: return@addSnapshotListener

            if (peerName == "Собеседник") {
                val name = when (role) {
                    "caller" -> (data["calleeUsername"] ?: data["toUsername"]) as? String
                    else -> (data["callerUsername"] ?: data["fromUsername"]) as? String
                } ?: otherUsername ?: "Собеседник"

                if (name != peerName) {
                    peerName = name
                }
            }

            val offerTimestamp = (data["offer"] as? Map<*, *>)?.get("timestamp") as? com.google.firebase.Timestamp

            if (role == "callee") {
                val offerMap = data["offer"] as? Map<*, *>
                val offer = offerMap?.get("sdp") as? String
                val offerTimestamp = offerMap?.get("timestamp") as? com.google.firebase.Timestamp

                if (!offer.isNullOrBlank() && offerTimestamp != null) {
                    val offerTime = offerTimestamp.toDate().time

                    // Применяем offer только если это новый offer
                    if (lastProcessedOfferTime == null || offerTime > lastProcessedOfferTime!!) {
                        Log.d("CallScreen", "📥 New/Updated offer received at $offerTime, applying...")
                        lastProcessedOfferTime = offerTime
                        WebRtcCallManager.applyRemoteOffer(offer)
                    } else {
                        Log.d("CallScreen", "⏭️ Skipping old offer (already processed)")
                    }
                } else if (!offer.isNullOrBlank() && !offerApplied.value) {
                    // Fallback для старых звонков без timestamp
                    Log.d("CallScreen", "📥 Applying initial OFFER (no timestamp)")
                    offerApplied.value = true
                    WebRtcCallManager.applyRemoteOffer(offer)
                }
            }

            if (role == "caller") {
                val answerMap = data["answer"] as? Map<*, *>
                val answer = answerMap?.get("sdp") as? String
                val answerTimestamp = answerMap?.get("timestamp") as? com.google.firebase.Timestamp

                if (!answer.isNullOrBlank() && answerTimestamp != null) {
                    val answerTime = answerTimestamp.toDate().time

                    // Применяем answer только если это новый answer
                    if (lastProcessedOfferTime == null || answerTime > lastProcessedOfferTime!!) {
                        Log.d("CallScreen", "📥 New/Updated answer received at $answerTime, applying...")
                        lastProcessedOfferTime = answerTime
                        WebRtcCallManager.applyRemoteAnswer(answer)
                        CallService.stopRingback(context)
                    } else {
                        Log.d("CallScreen", "⏭️ Skipping old answer (already processed)")
                    }
                } else if (!answer.isNullOrBlank() && !answerApplied.value) {
                    // Fallback для старых звонков без timestamp
                    Log.d("CallScreen", "📥 Applying initial ANSWER (no timestamp)")
                    answerApplied.value = true
                    WebRtcCallManager.applyRemoteAnswer(answer)
                    CallService.stopRingback(context)
                }
            }

            val ts = data["startedAt"]
            if (ts is com.google.firebase.Timestamp) {
                if (startedAtMillis == null) {
                    startedAtMillis = ts.toDate().time
                    CallService.stopRingback(context)
                }
            }

            val videoUpgradeTs = data["videoUpgradeRequest"]
            if (videoUpgradeTs != null && role == "callee") {
                val fromUser = (data["callerUsername"] ?: data["fromUsername"] ?: "Собеседник") as String
                WebRtcCallManager.onRemoteVideoUpgradeRequest(fromUser)
            }

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
    // ==================== REMOTE VIDEO TRACKING ====================

// Отслеживаем изменения remote video
    LaunchedEffect(isRemoteVideoEnabled) {
        Log.d("CallScreen", "========================================")
        Log.d("CallScreen", "📹 Remote video enabled: $isRemoteVideoEnabled")
        Log.d("CallScreen", "========================================")

        if (isRemoteVideoEnabled) {
            // Даем время для привязки renderer
            delay(300)
            Log.d("CallScreen", "Remote video should be visible now")
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

    ModernCallUI(
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
        ModernVideoUpgradeDialog(
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
//                     СОВРЕМЕННЫЙ ДИЗАЙН UI
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ModernCallUI(
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
    val isConnected = connectionState == WebRtcCallManager.ConnectionState.CONNECTED

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF4A90E2),
                        Color(0xFF7B68EE),
                        Color(0xFF9B59B6)
                    )
                )
            )
    ) {
        // ═══ ГЛАВНЫЙ КОНТЕНТ ═══
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                // ✅ ПРИОРИТЕТ 1: Если есть удаленное видео - показываем его на весь экран
                isRemoteVideoEnabled && !videoSwapped -> {
                    RemoteVideoFullScreen()
                    if (isLocalVideoEnabled) {
                        LocalVideoPip(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .zIndex(10f),
                            onSwap = onSwapVideo
                        )
                    }
                }

                // ✅ ПРИОРИТЕТ 2: Если своппнуто - показываем локальное на весь экран
                isLocalVideoEnabled && videoSwapped -> {
                    LocalVideoFullScreen()
                    if (isRemoteVideoEnabled) {
                        RemoteVideoPip(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .zIndex(10f),
                            onSwap = onSwapVideo
                        )
                    }
                }

                // ✅ ПРИОРИТЕТ 3: Если есть только локальное видео
                isLocalVideoEnabled && !isRemoteVideoEnabled -> {
                    LocalVideoFullScreen()
                }

                // ✅ ПРИОРИТЕТ 4: Если видео нет совсем - показываем аудио экран
                else -> {
                    ModernAudioContent(
                        peerName = peerName,
                        isConnected = isConnected
                    )
                }
            }
        }

        // ═══ ВЕРХНИЙ ОВЕРЛЕЙ С ИНФОРМАЦИЕЙ ═══
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(0.7f),
                            Color.Black.copy(0.3f),
                            Color.Transparent
                        )
                    )
                )
                .padding(top = 50.dp, bottom = 40.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Имя собеседника
            Text(
                text = peerName,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            )

            Spacer(Modifier.height(8.dp))

            // Статус звонка
            val statusText = when (connectionState) {
                WebRtcCallManager.ConnectionState.CONNECTING -> "Соединение..."
                WebRtcCallManager.ConnectionState.CONNECTED -> formatTime(elapsedMillis)
                WebRtcCallManager.ConnectionState.RECONNECTING -> "Переподключение..."
                WebRtcCallManager.ConnectionState.FAILED -> "Ошибка соединения"
                WebRtcCallManager.ConnectionState.DISCONNECTED -> "Отключено"
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (connectionState == WebRtcCallManager.ConnectionState.CONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(0.9f),
                    fontWeight = FontWeight.Medium,
                    fontSize = 18.sp
                )
            }

            // Индикаторы
            if (connectionState != WebRtcCallManager.ConnectionState.CONNECTED ||
                callQuality != WebRtcCallManager.Quality.Good) {
                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (connectionState != WebRtcCallManager.ConnectionState.CONNECTED) {
                        ModernStatusChip(connectionState)
                    }
                    if (callQuality != WebRtcCallManager.Quality.Good && isConnected) {
                        ModernQualityChip(callQuality)
                    }
                }
            }
        }

        // ═══ НИЖНИЙ ОВЕРЛЕЙ С КНОПКАМИ УПРАВЛЕНИЯ ═══
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(0.3f),
                            Color.Black.copy(0.8f)
                        )
                    )
                )
                .padding(bottom = 50.dp, top = 40.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Основные кнопки управления
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Микрофон
                ModernControlButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    isActive = !isMuted,
                    label = if (isMuted) "Откл" else "Вкл",
                    onClick = onToggleMic
                )

                // Видео
                ModernControlButton(
                    icon = if (isLocalVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    isActive = isLocalVideoEnabled,
                    label = if (isLocalVideoEnabled) "Видео" else "Видео",
                    onClick = onToggleVideo
                )

                // Динамик
                ModernControlButton(
                    icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                    isActive = isSpeakerOn,
                    label = if (isSpeakerOn) "Динамик" else "Наушники",
                    onClick = onToggleSpeaker
                )

                // Камера (только если видео включено)
                if (isLocalVideoEnabled) {
                    ModernControlButton(
                        icon = Icons.Default.Cameraswitch,
                        isActive = true,
                        label = "Камера",
                        onClick = onSwitchCamera
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // Кнопка завершить звонок
            Surface(
                onClick = onHangup,
                modifier = Modifier.size(70.dp),
                shape = CircleShape,
                color = Color(0xFFFF3B30),
                shadowElevation = 16.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "Завершить",
                        modifier = Modifier.size(34.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Завершить",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//                         VIDEO VIEWS
// ═══════════════════════════════════════════════════════════════
@Composable
private fun LocalVideoFullScreen() {
    var rendererReady by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                WebRtcCallManager.prepareRenderer(this, mirror = true, overlay = false)
                rendererReady = true
            }
        },
        update = { view ->
            if (rendererReady) {
                WebRtcCallManager.bindLocalRenderer(view)
            }
        }
    )
}

@Composable
private fun LocalVideoPip(
    modifier: Modifier,
    onSwap: () -> Unit
) {
    var rendererReady by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .size(120.dp, 160.dp)
            .clickable(onClick = onSwap),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black,
        shadowElevation = 16.dp
    ) {
        Box {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        WebRtcCallManager.prepareRenderer(this, mirror = true, overlay = true)
                        rendererReady = true
                    }
                },
                update = { view ->
                    if (rendererReady) {
                        WebRtcCallManager.bindLocalRenderer(view)
                    }
                }
            )

            Icon(
                Icons.Default.SwapVert,
                contentDescription = "Поменять",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp),
                tint = Color.White
            )
        }
    }
}

@Composable
private fun RemoteVideoFullScreen() {
    var rendererReady by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                WebRtcCallManager.prepareRenderer(this, mirror = false, overlay = false)
                rendererReady = true
            }
        },
        update = { view ->
            if (rendererReady) {
                WebRtcCallManager.bindRemoteRenderer(view)
            }
        }
    )
}

@Composable
private fun RemoteVideoPip(
    modifier: Modifier,
    onSwap: () -> Unit
) {
    var rendererReady by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .size(120.dp, 160.dp)
            .clickable(onClick = onSwap),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black,
        shadowElevation = 16.dp
    ) {
        Box {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        WebRtcCallManager.prepareRenderer(this, mirror = false, overlay = true)
                        rendererReady = true
                    }
                },
                update = { view ->
                    if (rendererReady) {
                        WebRtcCallManager.bindRemoteRenderer(view)
                    }
                }
            )

            Icon(
                Icons.Default.SwapVert,
                contentDescription = "Поменять",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(20.dp),
                tint = Color.White
            )
        }
    }
}
// ═══════════════════════════════════════════════════════════════
//                    AUDIO CONTENT (БЕЗ ВИДЕО)
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ModernAudioContent(
    peerName: String,
    isConnected: Boolean
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Анимированные круги
        if (!isConnected) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")

            repeat(3) { i ->
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2500, delayMillis = i * 800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "scale$i"
                )

                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2500, delayMillis = i * 800, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "alpha$i"
                )

                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .scale(scale)
                        .background(
                            Color.White.copy(alpha * 0.2f),
                            CircleShape
                        )
                )
            }
        }

        // Аватар
        Box(
            modifier = Modifier
                .size(180.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(0.4f),
                            Color.White.copy(0.2f)
                        )
                    ),
                    CircleShape
                )
                .border(4.dp, Color.White.copy(0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = peerName.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 72.sp
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//                         COMPONENTS
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ModernControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val backgroundColor = if (isActive) {
            Color.White.copy(0.3f)
        } else {
            Color(0xFFA45F5C).copy(0.9f)
        }

        Surface(
            onClick = onClick,
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = backgroundColor,
            shadowElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    modifier = Modifier.size(30.dp),
                    tint = Color.White
                )
            }
        }

        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ModernStatusChip(state: WebRtcCallManager.ConnectionState) {
    val (text, color) = when (state) {
        WebRtcCallManager.ConnectionState.CONNECTING -> "Соединение" to Color(0xFFFFA726)
        WebRtcCallManager.ConnectionState.RECONNECTING -> "Переподключение" to Color(0xFFFFA726)
        WebRtcCallManager.ConnectionState.FAILED -> "Ошибка" to Color(0xFFFF6B6B)
        WebRtcCallManager.ConnectionState.DISCONNECTED -> "Отключено" to Color(0xFF9E9E9E)
        else -> return
    }

    Surface(
        color = color.copy(0.3f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, color.copy(0.5f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun ModernQualityChip(quality: WebRtcCallManager.Quality) {
    val (text, color) = when (quality) {
        WebRtcCallManager.Quality.Medium -> "Среднее качество" to Color(0xFFFFA726)
        WebRtcCallManager.Quality.Poor -> "Плохое качество" to Color(0xFFFF6B6B)
        else -> return
    }

    Surface(
        color = color.copy(0.3f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, color.copy(0.5f))
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
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
            color = Color.White,
            shadowElevation = 24.dp
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF4A90E2),
                                    Color(0xFF7B68EE)
                                )
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(45.dp),
                        tint = Color.White
                    )
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "$fromUsername хочет включить видео",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF1A1A1A),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )

                Spacer(Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(2.dp, Color(0xFF4A90E2))
                    ) {
                        Text(
                            "Отклонить",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF4A90E2)
                        )
                    }

                    Button(
                        onClick = onAccept,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4A90E2)
                        )
                    ) {
                        Text(
                            "Включить",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
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

@Preview(showBackground = true, name = "Audio Call - Connecting")
@Composable
fun ModernCallUI_Preview_Audio_Connecting() {
    MaterialTheme {
        ModernCallUI(
            peerName = "Собеседник",
            elapsedMillis = 0,
            isMuted = false,
            isSpeakerOn = false,
            isLocalVideoEnabled = false,
            isRemoteVideoEnabled = false,
            connectionState = WebRtcCallManager.ConnectionState.CONNECTING,
            callQuality = WebRtcCallManager.Quality.Good,
            videoSwapped = false,
            onToggleMic = {}, onToggleSpeaker = {}, onToggleVideo = {}, onSwitchCamera = {}, onSwapVideo = {}, onHangup = {}
        )
    }
}

@Preview(showBackground = true, name = "Audio Call - Connected")
@Composable
fun ModernCallUI_Preview_Audio_Connected() {
    MaterialTheme {
        ModernCallUI(
            peerName = "Иван",
            elapsedMillis = 123000, // 2 минуты 3 секунды
            isMuted = false,
            isSpeakerOn = true,
            isLocalVideoEnabled = false,
            isRemoteVideoEnabled = false,
            connectionState = WebRtcCallManager.ConnectionState.CONNECTED,
            callQuality = WebRtcCallManager.Quality.Good,
            videoSwapped = false,
            onToggleMic = {}, onToggleSpeaker = {}, onToggleVideo = {}, onSwitchCamera = {}, onSwapVideo = {}, onHangup = {}
        )
    }
}

@Preview(showBackground = true, name = "Video Call - Connected")
@Composable
fun ModernCallUI_Preview_Video_Connected() {
    MaterialTheme {
        ModernCallUI(
            peerName = "Екатерина",
            elapsedMillis = 345000, // 5 минут 45 секунд
            isMuted = false,
            isSpeakerOn = true,
            isLocalVideoEnabled = true,
            isRemoteVideoEnabled = true,
            connectionState = WebRtcCallManager.ConnectionState.CONNECTED,
            callQuality = WebRtcCallManager.Quality.Good,
            videoSwapped = false,
            onToggleMic = {}, onToggleSpeaker = {}, onToggleVideo = {}, onSwitchCamera = {}, onSwapVideo = {}, onHangup = {}
        )
    }
}

@Preview(showBackground = true, name = "Call - Reconnecting")
@Composable
fun ModernCallUI_Preview_Reconnecting() {
    MaterialTheme {
        ModernCallUI(
            peerName = "Алексей",
            elapsedMillis = 88000,
            isMuted = false,
            isSpeakerOn = true,
            isLocalVideoEnabled = true,
            isRemoteVideoEnabled = false, // Remote video might be lost
            connectionState = WebRtcCallManager.ConnectionState.RECONNECTING,
            callQuality = WebRtcCallManager.Quality.Poor,
            videoSwapped = false,
            onToggleMic = {}, onToggleSpeaker = {}, onToggleVideo = {}, onSwitchCamera = {}, onSwapVideo = {}, onHangup = {}
        )
    }
}

@Preview(showBackground = true, name = "Video Upgrade Dialog")
@Composable
fun ModernVideoUpgradeDialog_Preview() {
    MaterialTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            ModernVideoUpgradeDialog(
                fromUsername = "Мария",
                onAccept = {}, onDecline = {}
            )
        }
    }
}
