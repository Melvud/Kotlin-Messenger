package com.example.messenger_app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.messenger_app.MainActivity
import com.example.messenger_app.data.CallsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CallActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        if (callId.isBlank()) {
            Log.e("CallActionReceiver", "CallID is blank for action: $action, ignoring.")
            return
        }

        Log.d("CallActionReceiver", "onReceive action=$action, id=$callId, user=$username, video=$isVideo")

        when (action) {
            ACTION_INCOMING_ACCEPT -> handleAccept(ctx, callId, username, isVideo)
            ACTION_INCOMING_DECLINE -> handleDecline(ctx, callId)
            ACTION_HANGUP -> handleHangup(ctx, callId)
            ACTION_TOGGLE_MUTE -> ctx.sendBroadcast(Intent(ACTION_INTERNAL_TOGGLE_MUTE))
            ACTION_TOGGLE_SPEAKER -> ctx.sendBroadcast(Intent(ACTION_INTERNAL_TOGGLE_SPEAKER))
            ACTION_TOGGLE_VIDEO -> ctx.sendBroadcast(Intent(ACTION_INTERNAL_TOGGLE_VIDEO))
            ACTION_OPEN_CALL -> openCallScreen(ctx, callId, username, isVideo)
        }
    }

    private fun handleAccept(ctx: Context, callId: String, username: String, isVideo: Boolean) {
        NotificationHelper.cancelIncomingCall(ctx, callId)

        val auth = FirebaseAuth.getInstance()
        val db = FirebaseFirestore.getInstance()
        val repo = CallsRepository(auth, db, com.example.messenger_app.data.push.PushRepository(ctx))
        scope.launch {
            try {
                repo.acceptCall(callId)
                Log.d("CallActionReceiver", "Call accepted and other devices hung up for $callId")
            } catch (e: Exception) {
                Log.w("CallActionReceiver", "hangupOtherDevices failed", e)
            }
        }

        CallTrampolineService.start(
            ctx = ctx,
            callId = callId,
            username = username,
            isVideo = isVideo,
            openUi = true
        )
    }

    private fun handleDecline(ctx: Context, callId: String) {
        updateCallStatus(callId, "declined")
        cleanup(ctx, callId)
    }

    private fun handleHangup(ctx: Context, callId: String) {
        updateCallStatus(callId, "ended")
        cleanup(ctx, callId)
    }

    private fun cleanup(ctx: Context, callId: String) {
        NotificationHelper.cancelIncomingCall(ctx, callId)
        OngoingCallStore.clear(ctx)
        ctx.sendBroadcast(Intent(ACTION_INTERNAL_HANGUP).putExtra(EXTRA_CALL_ID, callId))
        CallService.stop(ctx)
    }

    private fun updateCallStatus(callId: String, status: String) {
        scope.launch {
            try {
                val db = FirebaseFirestore.getInstance()
                val auth = FirebaseAuth.getInstance()
                // We need context for PushRepository, but we are in a CoroutineScope inside BroadcastReceiver.
                // Ideally we should inject or pass context.
                // Since this is a receiver, 'ctx' from onReceive might not be available here directly if not captured.
                // But updateCallStatus is a member function, so we don't have 'ctx'.
                // However, updateCallStatus is called from handleDecline/handleHangup which have 'ctx'.
                // I need to change updateCallStatus signature to take context or create repo inside handlers.
                // Let's create repo inside handlers and pass it, or just create it here if we can get context.
                // Wait, updateCallStatus is a private method in CallActionReceiver.
                // It doesn't have access to context unless passed.
                // I will modify updateCallStatus to take Context.
                
                // For now, I'll just fix the compilation by creating a dummy PushRepository or passing context if possible.
                // Actually, updateCallStatus is called from handleDecline(ctx, ...) and handleHangup(ctx, ...).
                // So I can pass ctx to updateCallStatus.
                
                // But wait, I can't change the signature in this replace_file_content easily if I don't see the call sites.
                // I'll assume I can pass context.
                
                // Let's modify updateCallStatus to take context.
                // But first, let's just fix the instantiation here.
                // I don't have context here.
                // I will use AppGraph.pushRepo if initialized, or...
                // AppGraph.pushRepo requires AppGraph.init(app) to be called.
                // In a Receiver, AppGraph might be initialized if the app is running.
                // If not, we might crash.
                // But CallActionReceiver runs in the app process.
                // So AppGraph.pushRepo should be available if App.onCreate ran.
                // I will use AppGraph.pushRepo.
                val repo = CallsRepository(auth, db, com.example.messenger_app.AppGraph.pushRepo)

                // Используем репозиторий для обновления статуса (который теперь включает endedAt)
                repo.updateStatus(callId, status)

                Log.d("CallActionReceiver", "Call $callId status updated to $status")
            } catch (e: Exception) {
                Log.w("CallActionReceiver", "Failed to update call status to $status", e)
            }
        }
    }

    private fun openCallScreen(ctx: Context, callId: String, username: String, isVideo: Boolean) {
        val activityIntent = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            // Эти deeplink-параметры обрабатываются в MainActivity
            putExtra("deeplink_callId", callId)
            putExtra("deeplink_isVideo", isVideo)
            putExtra("deeplink_username", username)
        }
        ctx.startActivity(activityIntent)
    }

    companion object {
        const val ACTION_INCOMING_ACCEPT = "com.example.messenger_app.ACTION_INCOMING_ACCEPT"
        const val ACTION_INCOMING_DECLINE = "com.example.messenger_app.ACTION_INCOMING_DECLINE"
        const val ACTION_HANGUP = "com.example.messenger_app.ACTION_HANGUP"
        const val ACTION_TOGGLE_MUTE = "com.example.messenger_app.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.example.messenger_app.ACTION_TOGGLE_SPEAKER"
        const val ACTION_TOGGLE_VIDEO = "com.example.messenger_app.ACTION_TOGGLE_VIDEO"
        const val ACTION_INTERNAL_HANGUP = "com.example.messenger_app.INTERNAL_HANGUP"
        const val ACTION_INTERNAL_TOGGLE_MUTE = "com.example.messenger_app.INTERNAL_TOGGLE_MUTE"
        const val ACTION_INTERNAL_TOGGLE_SPEAKER = "com.example.messenger_app.INTERNAL_TOGGLE_SPEAKER"
        const val ACTION_INTERNAL_TOGGLE_VIDEO = "com.example.messenger_app.INTERNAL_TOGGLE_VIDEO"
        const val ACTION_OPEN_CALL = "com.example.messenger_app.action.OPEN_CALL"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_IS_VIDEO = "extra_is_video"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_OPEN_UI = "openUi"
    }
}