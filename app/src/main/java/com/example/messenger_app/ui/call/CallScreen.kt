package com.example.messenger_app.ui.call

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.messenger_app.data.CallsRepository
import com.example.messenger_app.push.NotificationHelper
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

    val isMuted by WebRtcCallManager.isMuted.collectAsState()
    val isSpeakerOn by WebRtcCallManager.isSpeakerOn.collectAsState()
    val isVideoEnabled by WebRtcCallManager.isVideoEnabled.collectAsState()

    LaunchedEffect(callId) { NotificationHelper.cancelIncomingCall(context, callId) }

    val db = remember { FirebaseFirestore.getInstance() }
    val repo = remember { CallsRepository(FirebaseAuth.getInstance(), db) }
    val scope = rememberCoroutineScope()

    // ---------- Signaling delegate ----------
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
        }
        onDispose { WebRtcCallManager.signalingDelegate = null }
    }

    // ---------- Server-synced timer ----------
    var startedAtMillis by remember(callId) { mutableStateOf<Long?>(null) }
    var ended by remember(callId) { mutableStateOf(false) }
    var nowMs by remember(callId) { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startedAtMillis, ended) {
        while (startedAtMillis != null && !ended) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    var peerName by remember(callId) { mutableStateOf(otherUsername) }
    var offerApplied by remember(callId) { mutableStateOf(false) }
    var answerApplied by remember(callId) { mutableStateOf(false) }

    // ---------- Firestore listeners (SDP + ICE) ----------
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

            // apply remote SDP
            val offer = (data["offer"] as? Map<*, *>)?.get("sdp") as? String
            val answer = (data["answer"] as? Map<*, *>)?.get("sdp") as? String
            if (role == "callee" && !offer.isNullOrBlank() && !offerApplied) {
                offerApplied = true
                WebRtcCallManager.applyRemoteOffer(offer)
            }
            if (role == "caller" && !answer.isNullOrBlank() && !answerApplied) {
                answerApplied = true
                WebRtcCallManager.applyRemoteAnswer(answer)
            }

            // startedAt timestamp
            val ts = data["startedAt"]
            if (ts is com.google.firebase.Timestamp) {
                if (startedAtMillis == null) startedAtMillis = ts.toDate().time
            } else {
                val hasAnswer = (data["answer"] as? Map<*, *>)?.get("sdp") != null
                if (hasAnswer && startedAtMillis == null) {
                    db.runTransaction { tx ->
                        val snap2 = tx.get(callDoc)
                        if (snap2.get("startedAt") == null) {
                            tx.update(callDoc, "startedAt", FieldValue.serverTimestamp())
                        }
                    }
                }
            }

            if (data["endedAt"] != null && !ended) {
                ended = true
                WebRtcCallManager.endCall()
                onNavigateBack() // гарантированный выход
            }
        }

        val remoteWho = if (role == "caller") "callee" else "caller"
        val seen = mutableSetOf<String>()
        val iceReg = repo
            .candidatesCollection(callId, remoteWho)
            .addSnapshotListener { snap, _ ->
                snap?.documentChanges?.forEach { ch ->
                    if (ch.type == DocumentChange.Type.ADDED) {
                        val id = ch.document.id
                        if (!seen.add(id)) return@forEach
                        val mid = ch.document.getString("sdpMid") ?: "0"
                        val index = (ch.document.getLong("sdpMLineIndex") ?: 0L).toInt()
                        val cand = ch.document.getString("candidate") ?: return@forEach
                        WebRtcCallManager.addRemoteIceCandidate(mid, index, cand)
                    }
                }
            }

        onDispose {
            runCatching { docReg.remove() }
            runCatching { iceReg.remove() }
        }
    }

    // ---------- Init engine and start call ----------
    LaunchedEffect(Unit) {
        WebRtcCallManager.init(context.applicationContext)
    }

    // Compact permission request on incoming video call
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* no-op */ }

    LaunchedEffect(isVideo, role) {
        if (isVideo && role == "callee") {
            val need = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            val miss = need.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (miss.isNotEmpty()) permissionsLauncher.launch(miss.toTypedArray())
        }
    }

    LaunchedEffect(callId, isVideo, playRingback, role) {
        WebRtcCallManager.startCall(callId, isVideo, playRingback, role)
    }

    DisposableEffect(Unit) {
        onDispose { WebRtcCallManager.endCall() }
    }

    BackHandler {
        endOnBoth(db, callId)
        WebRtcCallManager.endCall()
        onNavigateBack()
    }

    val elapsed = startedAtMillis?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L

    if (isVideo) {
        VideoCallContent(
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            isVideoEnabled = isVideoEnabled,
            elapsedMillis = elapsed,
            onToggleMic = { WebRtcCallManager.toggleMic() },
            onToggleSpeaker = { WebRtcCallManager.toggleSpeaker() },
            onToggleVideo = { WebRtcCallManager.toggleVideo() },
            onSwitchCamera = { WebRtcCallManager.switchCamera() },
            onHangup = {
                endOnBoth(db, callId)
                WebRtcCallManager.endCall()
                onNavigateBack()
            }
        )
    } else {
        AudioCallContent(
            avatarLetter = (peerName?.firstOrNull() ?: callId.firstOrNull() ?: '?')
                .uppercaseChar().toString(),
            isMuted = isMuted,
            isSpeakerOn = isSpeakerOn,
            elapsedMillis = elapsed,
            onToggleMic = { WebRtcCallManager.toggleMic() },
            onToggleSpeaker = { WebRtcCallManager.toggleSpeaker() },
            onHangup = {
                endOnBoth(db, callId)
                WebRtcCallManager.endCall()
                onNavigateBack()
            }
        )
    }
}

private fun endOnBoth(db: FirebaseFirestore, callId: String) {
    runCatching {
        db.collection("calls").document(callId)
            .update(mapOf("endedAt" to FieldValue.serverTimestamp(), "status" to "ended"))
    }
}

/* ======================== AUDIO UI ======================== */

@Composable
private fun AudioCallContent(
    avatarLetter: String,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    elapsedMillis: Long,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onHangup: () -> Unit
) {
    val controlSize = 80.dp

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF3730A3), Color(0xFF7C3AED))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = avatarLetter,
                        color = Color.White,
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (elapsedMillis > 0) formatElapsed(elapsedMillis) else "Подключение…",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 18.sp
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LargeControlButton(
                        size = controlSize,
                        onClick = onToggleMic,
                        container = Color.White.copy(alpha = 0.15f),
                        content = Color.White
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = null
                        )
                    }

                    LargeControlButton(
                        size = controlSize,
                        onClick = onToggleSpeaker,
                        container = Color.White.copy(alpha = 0.15f),
                        content = Color.White
                    ) {
                        Icon(
                            imageVector = if (isSpeakerOn) Icons.Filled.SpeakerPhone else Icons.Filled.Speaker,
                            contentDescription = null
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                LargeControlButton(
                    size = controlSize,
                    onClick = onHangup,
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError
                ) { Icon(Icons.Filled.CallEnd, contentDescription = null) }
            }
        }
    }
}

/* ======================== VIDEO UI ======================== */

@Composable
private fun VideoCallContent(
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isVideoEnabled: Boolean,
    elapsedMillis: Long,
    onToggleMic: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onHangup: () -> Unit
) {
    val context = LocalContext.current
    val controlSize = 56.dp
    val hangupSize = 80.dp
    var localPrimary by remember { mutableStateOf(false) } // false: remote big; true: local big
    val pipSize = 140.dp

    // Создаём стабильные инстансы рендеров, чтобы не пересоздавались при свопе
    val remoteRenderer = remember {
        SurfaceViewRenderer(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }
    val localRenderer = remember {
        SurfaceViewRenderer(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    // Готовим и привязываем ТОЛЬКО один раз
    LaunchedEffect(Unit) {
        // Важно: оба — overlay, чтобы любой мог быть PIP; onTop = false
        WebRtcCallManager.prepareRenderer(remoteRenderer, mirror = false, overlay = true)
        WebRtcCallManager.prepareRenderer(localRenderer, mirror = true, overlay = true)

        WebRtcCallManager.bindRemoteRenderer(remoteRenderer)
        WebRtcCallManager.bindLocalRenderer(localRenderer)

        // Попросим верхний PIP оказаться поверх (на всякий случай)
        remoteRenderer.post { remoteRenderer.bringToFront() }
        localRenderer.post { localRenderer.bringToFront() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {

            // FULLSCREEN: кладём нужный View
            if (localPrimary) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { localRenderer }
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { remoteRenderer }
                )
            }

            // PIP (всегда поверх через bringToFront)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(pipSize)
                    .clip(MaterialTheme.shapes.large)
                    .clickable {
                        localPrimary = !localPrimary
                        // Гарантируем порядок поверх big
                        if (localPrimary) {
                            remoteRenderer.bringToFront()
                        } else {
                            localRenderer.bringToFront()
                        }
                    }
            ) {
                if (localPrimary) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { remoteRenderer },
                        update = { it.visibility = View.VISIBLE }
                    )
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { localRenderer },
                        update = { it.visibility = View.VISIBLE }
                    )
                }
            }

            // Timer
            if (elapsedMillis > 0) {
                Text(
                    text = formatElapsed(elapsedMillis),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 14.sp
                )
            }

            // Controls
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LargeControlButton(
                        size = controlSize,
                        onClick = onToggleMic,
                        container = Color.Black.copy(alpha = 0.4f),
                        content = Color.White
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = null
                        )
                    }

                    LargeControlButton(
                        size = controlSize,
                        onClick = onToggleVideo,
                        container = Color.Black.copy(alpha = 0.4f),
                        content = Color.White
                    ) {
                        Icon(
                            imageVector = if (isVideoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            contentDescription = null
                        )
                    }

                    LargeControlButton(
                        size = controlSize,
                        onClick = onSwitchCamera,
                        container = Color.Black.copy(alpha = 0.4f),
                        content = Color.White
                    ) { Icon(Icons.Filled.Cameraswitch, contentDescription = null) }
                }

                Spacer(Modifier.height(18.dp))

                LargeControlButton(
                    size = hangupSize,
                    onClick = onHangup,
                    container = MaterialTheme.colorScheme.error,
                    content = MaterialTheme.colorScheme.onError
                ) { Icon(Icons.Filled.CallEnd, contentDescription = null) }
            }
        }
    }
}

/* ======================== Shared UI bits ======================== */

@Composable
private fun LargeControlButton(
    size: Dp,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    contentSlot: @Composable () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = container,
            contentColor = content
        ),
        modifier = Modifier.size(size)
    ) { contentSlot() }
}

private fun formatElapsed(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
