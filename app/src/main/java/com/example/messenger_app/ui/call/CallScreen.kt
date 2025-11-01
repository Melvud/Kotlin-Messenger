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
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

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

    val isMuted by WebRtcCallManager.isMuted.collectAsState()
    val isSpeakerOn by WebRtcCallManager.isSpeakerOn.collectAsState()
    val isLocalVideoEnabled by WebRtcCallManager.isVideoEnabled.collectAsState()
    val isRemoteVideoEnabled by WebRtcCallManager.isRemoteVideoEnabled.collectAsState()
    val connectionState by WebRtcCallManager.connectionState.collectAsState()
    val callQuality by WebRtcCallManager.callQuality.collectAsState()
    val videoUpgradeRequest by WebRtcCallManager.videoUpgradeRequest.collectAsState()

    // ✅ FIX: Получаем время начала из WebRtcCallManager
    val callStartedAtMs by WebRtcCallManager.callStartedAtMs.collectAsState()

    var callEnded by remember { mutableStateOf(false) }
    var showEndCallDialog by remember { mutableStateOf(false) }
    var peerName by remember { mutableStateOf(otherUsername ?: "Собеседник") }

    val db = remember { FirebaseFirestore.getInstance() }
    val repo = remember { CallsRepository(FirebaseAuth.getInstance(), db) }

    var lastProcessedOfferTime by remember { mutableStateOf<Long?>(null) }
    var lastProcessedAnswerTime by remember { mutableStateOf<Long?>(null) }

    // ✅ FIX: Таймер использует callStartedAtMs из WebRtcCallManager
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(callStartedAtMs) {
        if (callStartedAtMs != null) {
            while (!callEnded) {
                delay(1000)
                nowMs = System.currentTimeMillis()
            }
        }
    }

    val elapsedMillis = if (callStartedAtMs != null && !callEnded) {
        (nowMs - callStartedAtMs!!).coerceAtLeast(0)
    } else 0L

    DisposableEffect(Unit) {
        Log.d("CallScreen", """
            ════════════════════════════════════════
            🚀 CALL SCREEN STARTED
            callId: $callId
            isVideo: $isVideo
            role: $role
            otherUsername: $otherUsername
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

    // ✅ FIX: Добавлен callback onCallStarted
    DisposableEffect(Unit) {
        WebRtcCallManager.signalingDelegate = object : WebRtcCallManager.SignalingDelegate {

            override fun onLocalDescription(callId: String, sdp: org.webrtc.SessionDescription) {
                Log.d("CallScreen", "📤 SDP: ${sdp.type} (length: ${sdp.description.length})")

                scope.launch(Dispatchers.IO) {
                    try {
                        if (role == "caller") {
                            repo.setOffer(callId, sdp.description, "offer")
                            Log.d("CallScreen", "✅ Offer sent to Firestore")
                        } else {
                            repo.setAnswer(callId, sdp.description, "answer")
                            Log.d("CallScreen", "✅ Answer sent to Firestore")
                        }
                    } catch (e: Exception) {
                        Log.e("CallScreen", "❌ Failed to send SDP: ${e.message}", e)
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

                scope.launch(Dispatchers.IO) {
                    try {
                        repo.candidatesCollection(callId, who).add(map)
                    } catch (e: Exception) {
                        Log.e("CallScreen", "❌ Failed to send ICE: ${e.message}")
                    }
                }
            }

            override fun onCallTimeout(callId: String) {
                Log.w("CallScreen", "⏰ Call timeout!")
                scope.launch(Dispatchers.IO) {
                    try {
                        repo.updateStatus(callId, "timeout")
                        db.collection("calls").document(callId)
                            .update("endedAt", FieldValue.serverTimestamp())
                    } catch (e: Exception) {
                        Log.e("CallScreen", "Failed to update timeout status", e)
                    }
                }
            }

            override fun onConnectionFailed(callId: String) {
                Log.e("CallScreen", "❌ Connection failed!")
                scope.launch(Dispatchers.IO) {
                    try {
                        repo.updateStatus(callId, "failed")
                        db.collection("calls").document(callId)
                            .update("endedAt", FieldValue.serverTimestamp())
                    } catch (e: Exception) {
                        Log.e("CallScreen", "Failed to update failed status", e)
                    }
                }
            }

            override fun onVideoUpgradeRequest() {
                Log.d("CallScreen", "📹 Requesting video upgrade")
                scope.launch(Dispatchers.IO) {
                    try {
                        db.collection("calls").document(callId)
                            .update("videoUpgradeRequest", FieldValue.serverTimestamp())
                    } catch (e: Exception) {
                        Log.e("CallScreen", "Failed to request video upgrade", e)
                    }
                }
            }

            // ✅ НОВОЕ: Сохраняем время начала в Firestore
            override fun onCallStarted(startTimeMs: Long) {
                Log.d("CallScreen", "⏱️ Call started callback: $startTimeMs")
                scope.launch(Dispatchers.IO) {
                    try {
                        db.collection("calls").document(callId)
                            .update("startedAt", Timestamp(startTimeMs / 1000, 0))
                        Log.d("CallScreen", "✅ startedAt saved to Firestore: $startTimeMs")
                    } catch (e: Exception) {
                        Log.e("CallScreen", "Failed to save startedAt", e)
                    }
                }
            }
        }

        onDispose {
            WebRtcCallManager.signalingDelegate = null
        }
    }

    DisposableEffect(Unit) {
        val callDoc = db.collection("calls").document(callId)

        val docReg = callDoc.addSnapshotListener { snap, error ->
            if (error != null) {
                Log.e("CallScreen", "Firestore listener error", error)
                return@addSnapshotListener
            }

            val data = snap?.data ?: return@addSnapshotListener

            if (peerName == "Собеседник") {
                val name = when (role) {
                    "caller" -> (data["calleeUsername"] ?: data["toUsername"]) as? String
                    else -> (data["callerUsername"] ?: data["fromUsername"]) as? String
                } ?: otherUsername ?: "Собеседник"

                if (name != peerName) {
                    peerName = name
                    Log.d("CallScreen", "✅ Peer name updated: $peerName")
                }
            }

            // ✅ FIX: Handle offers regardless of role (for renegotiation)
            val offerMap = data["offer"] as? Map<*, *>
            val offer = offerMap?.get("sdp") as? String
            val offerTimestamp = offerMap?.get("timestamp") as? Timestamp

            if (!offer.isNullOrBlank() && offerTimestamp != null) {
                val offerTime = offerTimestamp.toDate().time

                if (lastProcessedOfferTime == null || offerTime > lastProcessedOfferTime!!) {
                    Log.d("CallScreen", "📥 Applying OFFER (timestamp: $offerTime, role: $role)")
                    lastProcessedOfferTime = offerTime
                    WebRtcCallManager.applyRemoteOffer(offer)
                } else {
                    Log.d("CallScreen", "⚠️ Skipping old offer (timestamp: $offerTime)")
                }
            }

            // ✅ FIX: Handle answers regardless of role (for renegotiation)
            val answerMap = data["answer"] as? Map<*, *>
            val answer = answerMap?.get("sdp") as? String
            val answerTimestamp = answerMap?.get("timestamp") as? Timestamp

            if (!answer.isNullOrBlank() && answerTimestamp != null) {
                val answerTime = answerTimestamp.toDate().time

                if (lastProcessedAnswerTime == null || answerTime > lastProcessedAnswerTime!!) {
                    Log.d("CallScreen", "📥 Applying ANSWER (timestamp: $answerTime, role: $role)")
                    lastProcessedAnswerTime = answerTime
                    WebRtcCallManager.applyRemoteAnswer(answer)
                    CallService.stopRingback(context)
                } else {
                    Log.d("CallScreen", "⚠️ Skipping old answer (timestamp: $answerTime)")
                }
            }

            // ✅ FIX: Используем setCallStartTime вместо прямой установки
            val ts = data["startedAt"]
            if (ts is Timestamp) {
                val firestoreTime = ts.toDate().time
                WebRtcCallManager.setCallStartTime(firestoreTime)
                CallService.stopRingback(context)
            }

            val videoUpgradeTs = data["videoUpgradeRequest"]
            if (videoUpgradeTs != null) {
                val fromUser = when (role) {
                    "callee" -> (data["callerUsername"] ?: data["fromUsername"] ?: "Собеседник") as String
                    else -> (data["calleeUsername"] ?: data["toUsername"] ?: "Собеседник") as String
                }
                Log.d("CallScreen", "📹 Video upgrade request from: $fromUser")
                WebRtcCallManager.onRemoteVideoUpgradeRequest(fromUser)
            }

            if (data["endedAt"] != null && !callEnded) {
                Log.d("CallScreen", "📞 Call ended remotely")
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
                if (error != null) {
                    Log.e("CallScreen", "ICE listener error", error)
                    return@addSnapshotListener
                }

                snap?.documentChanges?.forEach { dc ->
                    if (dc.type == DocumentChange.Type.ADDED) {
                        val d = dc.document.data
                        val mid = d["sdpMid"] as? String ?: "0"
                        val idx = (d["sdpMLineIndex"] as? Number)?.toInt() ?: 0
                        val cand = d["candidate"] as? String ?: return@forEach

                        Log.d("CallScreen", "📥 Remote ICE: $mid:$idx")
                        WebRtcCallManager.addRemoteIceCandidate(mid, idx, cand)
                    }
                }
            }

        onDispose {
            docReg.remove()
            iceReg.remove()
            Log.d("CallScreen", "🔌 Firestore listeners removed")
        }
    }

    LaunchedEffect(isRemoteVideoEnabled) {
        Log.d("CallScreen", "════════════════════════════════════════")
        Log.d("CallScreen", "📹 Remote video enabled: $isRemoteVideoEnabled")
        Log.d("CallScreen", "════════════════════════════════════════")

        if (isRemoteVideoEnabled) {
            delay(300)
            Log.d("CallScreen", "Remote video should be visible now")
        }
    }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    CallActionReceiver.ACTION_INTERNAL_HANGUP -> {
                        Log.d("CallScreen", "🔴 Hangup from notification")
                        callEnded = true
                        WebRtcCallManager.endCall()
                        onNavigateBack()
                    }
                    CallActionReceiver.ACTION_INTERNAL_TOGGLE_MUTE -> {
                        Log.d("CallScreen", "🎤 Toggle mute from notification")
                        WebRtcCallManager.toggleMic()
                    }
                    CallActionReceiver.ACTION_INTERNAL_TOGGLE_SPEAKER -> {
                        Log.d("CallScreen", "🔊 Toggle speaker from notification")
                        WebRtcCallManager.toggleSpeaker()
                    }
                    CallActionReceiver.ACTION_INTERNAL_TOGGLE_VIDEO -> {
                        Log.d("CallScreen", "📹 Toggle video from notification")
                        WebRtcCallManager.toggleVideo()
                    }
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
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w("CallScreen", "Failed to unregister receiver", e)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (!callEnded) {
                scope.launch(Dispatchers.IO) {
                    try {
                        db.collection("calls").document(callId)
                            .update("endedAt", FieldValue.serverTimestamp())
                    } catch (e: Exception) {
                        Log.e("CallScreen", "Failed to end call on dispose", e)
                    }
                }
            }
        }
    }

    BackHandler {
        showEndCallDialog = true
    }

    // ✅ FIX: Правильная логика отображения видео
    ModernCallUI(
        peerName = peerName,
        elapsedMillis = elapsedMillis,
        isMuted = isMuted,
        isSpeakerOn = isSpeakerOn,
        isLocalVideoEnabled = isLocalVideoEnabled,
        isRemoteVideoEnabled = isRemoteVideoEnabled,
        connectionState = connectionState,
        callQuality = callQuality,
        onToggleMic = {
            Log.d("CallScreen", "🎤 Toggle mic")
            WebRtcCallManager.toggleMic()
        },
        onToggleSpeaker = {
            Log.d("CallScreen", "🔊 Toggle speaker")
            WebRtcCallManager.toggleSpeaker()
        },
        onToggleVideo = {
            Log.d("CallScreen", "📹 Toggle video")
            WebRtcCallManager.toggleVideo()
        },
        onSwitchCamera = {
            Log.d("CallScreen", "🔄 Switch camera")
            WebRtcCallManager.switchCamera()
        },
        onHangup = {
            Log.d("CallScreen", "🔴 Hangup button pressed")
            callEnded = true

            scope.launch(Dispatchers.IO) {
                try {
                    db.collection("calls").document(callId)
                        .update("endedAt", FieldValue.serverTimestamp())
                } catch (e: Exception) {
                    Log.e("CallScreen", "Failed to update endedAt", e)
                }
            }

            WebRtcCallManager.endCall()
            CallService.stop(context)
            OngoingCallStore.clear(context)
            onNavigateBack()
        }
    )

    videoUpgradeRequest?.let { request ->
        ModernVideoUpgradeDialog(
            fromUsername = request.fromUsername,
            onAccept = {
                Log.d("CallScreen", "✅ Video upgrade accepted")
                WebRtcCallManager.acceptVideoUpgrade()
            },
            onDecline = {
                Log.d("CallScreen", "❌ Video upgrade declined")
                WebRtcCallManager.declineVideoUpgrade()
            }
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
                            try {
                                db.collection("calls").document(callId)
                                    .update("endedAt", FieldValue.serverTimestamp())
                            } catch (e: Exception) {
                                Log.e("CallScreen", "Failed to update endedAt", e)
                            }
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

// ✅ FIX: Правильная логика отображения видео
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
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
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
        // ═══ КОНТЕНТ ЗВОНКА ═══
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                // ✅ ПРИОРИТЕТ 1: Если есть УДАЛЕННОЕ видео - показываем ЕГО на весь экран
                isRemoteVideoEnabled -> {
                    Log.d("ModernCallUI", "✅ Showing REMOTE video fullscreen")
                    RemoteVideoFullScreen()

                    // Показываем локальное в PiP только если оно включено
                    if (isLocalVideoEnabled) {
                        LocalVideoPip(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .zIndex(10f)
                        )
                    }
                }

                // ✅ ПРИОРИТЕТ 2: Если есть только ЛОКАЛЬНОЕ видео
                isLocalVideoEnabled -> {
                    Log.d("ModernCallUI", "✅ Showing LOCAL video fullscreen")
                    LocalVideoFullScreen()
                }

                // ✅ ПРИОРИТЕТ 3: Аудио режим (нет видео совсем)
                else -> {
                    Log.d("ModernCallUI", "✅ Showing AUDIO mode")
                    ModernAudioContent(
                        peerName = peerName,
                        isConnected = isConnected
                    )
                }
            }
        }

        // ═══ ВЕРХНЯЯ ПАНЕЛЬ С ИНФОРМАЦИЕЙ ═══
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
            Text(
                text = peerName,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp
            )

            Spacer(Modifier.height(8.dp))

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

        // ═══ НИЖНЯЯ ПАНЕЛЬ С КНОПКАМИ ═══
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ModernControlButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    isActive = !isMuted,
                    label = if (isMuted) "Откл" else "Вкл",
                    onClick = onToggleMic
                )

                ModernControlButton(
                    icon = if (isLocalVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    isActive = isLocalVideoEnabled,
                    label = "Видео",
                    onClick = onToggleVideo
                )

                ModernControlButton(
                    icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                    isActive = isSpeakerOn,
                    label = if (isSpeakerOn) "Динамик" else "Наушники",
                    onClick = onToggleSpeaker
                )

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

@Composable
private fun LocalVideoFullScreen() {
    var rendererReady by remember { mutableStateOf(false) }

    Log.d("LocalVideoFullScreen", "📹 Rendering local video fullscreen (recomposition)")

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            Log.d("LocalVideoFullScreen", "📹 Creating renderer (factory)")
            SurfaceViewRenderer(ctx).apply {
                WebRtcCallManager.prepareRenderer(this, mirror = true, overlay = false)
                rendererReady = true
            }
        },
        update = { view ->
            if (rendererReady) {
                Log.d("LocalVideoFullScreen", "📹 Binding local renderer (update)")
                WebRtcCallManager.bindLocalRenderer(view)
            }
        }
    )
}

// ✅ FIX: Локальное видео БЕЗ зеркала в PiP
@Composable
private fun LocalVideoPip(modifier: Modifier) {
    var rendererReady by remember { mutableStateOf(false) }

    Log.d("LocalVideoPip", "📹 Rendering local video PiP (recomposition)")

    Surface(
        modifier = modifier.size(120.dp, 160.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Black,
        shadowElevation = 16.dp
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Log.d("LocalVideoPip", "📹 Creating renderer (factory)")
                SurfaceViewRenderer(ctx).apply {
                    // ✅ FIX: mirror=true для локального видео
                    WebRtcCallManager.prepareRenderer(this, mirror = true, overlay = true)
                    rendererReady = true
                }
            },
            update = { view ->
                if (rendererReady) {
                    Log.d("LocalVideoPip", "📹 Binding local renderer (update)")
                    WebRtcCallManager.bindLocalRenderer(view)
                }
            }
        )
    }
}

// ✅ FIX: Удаленное видео БЕЗ зеркала
@Composable
private fun RemoteVideoFullScreen() {
    var rendererReady by remember { mutableStateOf(false) }

    Log.d("RemoteVideoFullScreen", "📹 Rendering remote video fullscreen (recomposition)")

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            Log.d("RemoteVideoFullScreen", "📹 Creating renderer (factory)")
            SurfaceViewRenderer(ctx).apply {
                // ✅ FIX: mirror=false для удаленного видео!
                WebRtcCallManager.prepareRenderer(this, mirror = false, overlay = false)
                rendererReady = true
            }
        },
        update = { view ->
            if (rendererReady) {
                Log.d("RemoteVideoFullScreen", "📹 Binding remote renderer (update)")
                WebRtcCallManager.bindRemoteRenderer(view)
            }
        }
    )
}

@Composable
private fun ModernAudioContent(
    peerName: String,
    isConnected: Boolean
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
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