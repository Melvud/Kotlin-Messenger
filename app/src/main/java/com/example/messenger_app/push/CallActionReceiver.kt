package com.example.messenger_app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.messenger_app.data.CallsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Обработчик кнопок из уведомлений (Принять/Отклонить/Положить и т.д.).
 * ИСПРАВЛЕНО: При принятии звонка сразу инициализируется соединение
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
                // ИСПРАВЛЕНИЕ 1: Закрываем «входящий» биппер и СРАЗУ стартуем основной сервис звонка
                NotificationHelper.cancelIncomingCall(ctx, callId)

                // Сохраняем состояние звонка
                OngoingCallStore.save(ctx, callId, if (isVideo) "video" else "audio", username)

                // Уведомляем другие устройства пользователя о принятии звонка
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

                // Стартуем foreground сервис с открытием UI И инициализацией соединения
                CallService.start(
                    ctx = ctx,
                    callId = callId,
                    username = username,
                    isVideo = isVideo,
                    openUi = true,
                    playRingback = false,
                    initializeConnection = true // КЛЮЧЕВОЕ ИЗМЕНЕНИЕ
                )
            }

            ACTION_INCOMING_DECLINE, ACTION_HANGUP -> {
                NotificationHelper.cancelIncomingCall(ctx, callId)
                OngoingCallStore.clear(ctx)

                // Отправляем внутренний broadcast для UI/менеджера
                ctx.sendBroadcast(Intent(ACTION_INTERNAL_HANGUP))
                CallService.stop(ctx)
            }

            ACTION_TOGGLE_MUTE -> ctx.sendBroadcast(Intent(ACTION_INTERNAL_TOGGLE_MUTE))
            ACTION_TOGGLE_SPEAKER -> ctx.sendBroadcast(Intent(ACTION_INTERNAL_TOGGLE_SPEAKER))
            ACTION_TOGGLE_VIDEO -> ctx.sendBroadcast(Intent(ACTION_INTERNAL_TOGGLE_VIDEO))

            ACTION_OPEN_CALL -> {
                // Открыть экран звонка из шторки (уже активный звонок)
                CallService.start(ctx, callId, username, isVideo, openUi = true)
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