package com.example.messenger_app.push

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Короткоживущий «трамплин», который стартует как foreground service,
 * чтобы система дала нам время запустить настоящий CallService.
 */
class CallTrampolineService : Service() {

    companion object {
        private const val TAG = "CallTrampolineService"

        fun start(
            ctx: Context,
            callId: String,
            username: String,
            isVideo: Boolean,
            openUi: Boolean
        ) {
            val intent = Intent(ctx, CallTrampolineService::class.java).apply {
                putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
                putExtra(CallActionReceiver.EXTRA_USERNAME, username)
                putExtra(CallActionReceiver.EXTRA_IS_VIDEO, isVideo)
                putExtra(CallActionReceiver.EXTRA_OPEN_UI, openUi)
            }
            try {
                ContextCompat.startForegroundService(ctx, intent)
                Log.d(TAG, "✅ Trampoline service started for callId=$callId")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start trampoline service", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Trampoline onStartCommand")

        // 1. Показываем базовое уведомление, чтобы удовлетворить требование startForeground()
        val notification = NotificationHelper.createBaseNotification(
            context = this,
            channelId = NotificationHelper.INCOMING_CALL_CHANNEL_ID, // Используем тот же канал
            title = "Соединение...",
            text = "Пожалуйста, подождите"
        )
        startForeground(NotificationHelper.TRAMPOLINE_NOTIFICATION_ID, notification)

        // 2. Извлекаем данные и стартуем настоящий CallService
        val callId = intent?.getStringExtra(CallActionReceiver.EXTRA_CALL_ID)
        val username = intent?.getStringExtra(CallActionReceiver.EXTRA_USERNAME)
        val isVideo = intent?.getBooleanExtra(CallActionReceiver.EXTRA_IS_VIDEO, false) ?: false
        val openUi = intent?.getBooleanExtra(CallActionReceiver.EXTRA_OPEN_UI, false) ?: false

        if (callId.isNullOrBlank() || username.isNullOrBlank()) {
            Log.e(TAG, "❌ Missing callId or username in trampoline.")
            stopSelf()
            return START_NOT_STICKY
        }

        Log.d(TAG, "-> Starting main CallService from trampoline")
        CallService.start(
            ctx = this,
            callId = callId,
            username = username,
            isVideo = isVideo,
            openUi = openUi,
            playRingback = false // Рингбэк здесь не нужен
        )

        // 3. Завершаем работу трамплина
        stopSelf()

        return START_NOT_STICKY
    }
}