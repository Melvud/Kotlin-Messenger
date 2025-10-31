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

/**
 * ИСПРАВЛЕНО: Правильная обработка принятия звонка с передачей role
 */
class CallActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)

        Log.d("CallActionReceiver", "onReceive action=$action, id=$callId, user=$username, video=$isVideo")

        when (action) {
            ACTION_INCOMING_ACCEPT -> {
                // Закрываем уведомление
                NotificationHelper.cancelIncomingCall(ctx, callId)

                // Сохраняем состояние звонка
                OngoingCallStore.save(ctx, callId, if (isVideo) "video" else "audio", username)

                // Уведомляем другие устройства
                val auth = FirebaseAuth.getInstance()
                val db = FirebaseFirestore.getInstance()
                val repo = CallsRepository(auth, db)
                scope.launch {
                    try {
                        repo.hangupOtherDevices(callId)
                    } catch (e: Exception) {
                        Log.w("CallActionReceiver", "hangupOtherDevices failed", e)
                    }
                }

                // ИСПРАВЛЕНИЕ: Передаем правильные параметры для входящего звонка
                val activityIntent = Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("action", "accept")
                    putExtra("callId", callId)
                    putExtra("isVideo", isVideo)
                    putExtra("username", username)
                    putExtra("role", "callee") // ВАЖНО: явно указываем роль
                    putExtra("playRingback", false) // Входящий звонок не играет ringback
                }
                ctx.startActivity(activityIntent)
            }

            ACTION_INCOMING_DECLINE, ACTION_HANGUP -> {
                NotificationHelper.cancelIncomingCall(ctx, callId)
                OngoingCallStore.clear(ctx)

                // Обновляем статус звонка
                val auth = FirebaseAuth.getInstance()
                val db = FirebaseFirestore.getInstance()
                scope.launch {
                    try {
                        db.collection("calls").document(callId)
                            .update(
                                "status", if (action == ACTION_INCOMING_DECLINE) "declined" else "ended",
                                "endedAt", com.google.firebase.firestore.FieldValue.serverTimestamp()
                            )
                    } catch (e: Exception) {
                        Log.w("CallActionReceiver", "Failed to update call status", e)
                    }
                }

                // Отправляем внутренний broadcast
                ctx.sendBroadcast(Intent(ACTION_INTERNAL_HANGUP).apply {
                    putExtra(EXTRA_CALL_ID, callId)
                })

                // Останавливаем сервис
                CallService.stop(ctx)
            }

            ACTION_TOGGLE_MUTE -> ctx.sendBroadcast(Intent(ACTION_INTERNAL_TOGGLE_MUTE))
            ACTION_TOGGLE_SPEAKER -> ctx.sendBroadcast(Intent(ACTION_INTERNAL_TOGGLE_SPEAKER))
            ACTION_TOGGLE_VIDEO -> ctx.sendBroadcast(Intent(ACTION_INTERNAL_TOGGLE_VIDEO))

            ACTION_OPEN_CALL -> {
                // Открыть экран звонка из шторки
                val activityIntent = Intent(ctx, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("deeplink_callId", callId)
                    putExtra("deeplink_isVideo", isVideo)
                    putExtra("deeplink_username", username)
                }
                ctx.startActivity(activityIntent)
            }
        }
    }

    companion object {
        // Экшены из уведомлений
        const val ACTION_INCOMING_ACCEPT = "com.example.messenger_app.ACTION_INCOMING_ACCEPT"
        const val ACTION_INCOMING_DECLINE = "com.example.messenger_app.ACTION_INCOMING_DECLINE"
        const val ACTION_HANGUP = "com.example.messenger_app.ACTION_HANGUP"
        const val ACTION_TOGGLE_MUTE = "com.example.messenger_app.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.example.messenger_app.ACTION_TOGGLE_SPEAKER"
        const val ACTION_TOGGLE_VIDEO = "com.example.messenger_app.ACTION_TOGGLE_VIDEO"

        // Внутренние экшены для UI
        const val ACTION_INTERNAL_HANGUP = "com.example.messenger_app.INTERNAL_HANGUP"
        const val ACTION_INTERNAL_TOGGLE_MUTE = "com.example.messenger_app.INTERNAL_TOGGLE_MUTE"
        const val ACTION_INTERNAL_TOGGLE_SPEAKER = "com.example.messenger_app.INTERNAL_TOGGLE_SPEAKER"
        const val ACTION_INTERNAL_TOGGLE_VIDEO = "com.example.messenger_app.INTERNAL_TOGGLE_VIDEO"

        // Открыть экран звонка
        const val ACTION_OPEN_CALL = "com.example.messenger_app.action.OPEN_CALL"

        // Extras
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_IS_VIDEO = "extra_is_video"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_OPEN_UI = "openUi"
    }
}