package com.example.messenger_app.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Короткоживущий «трамплин». Важное изменение:
 * — больше НЕ стартуемся как foreground (чтобы не падать, если не позвали startForeground()).
 * — просто пробрасываем параметры в настоящий CallService и завершаемся.
 */
class CallTrampolineService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId   = intent?.getStringExtra(CallActionReceiver.EXTRA_CALL_ID).orEmpty()
        val username = intent?.getStringExtra(CallActionReceiver.EXTRA_USERNAME).orEmpty()
        val isVideo  = intent?.getBooleanExtra(CallActionReceiver.EXTRA_IS_VIDEO, false) ?: false
        val openUi   = intent?.getBooleanExtra(CallActionReceiver.EXTRA_OPEN_UI, false) ?: false

        if (callId.isNotBlank()) {
            CallService.start(this, callId, username, isVideo, openUi)
        }
        stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context, callId: String, username: String, isVideo: Boolean, openUi: Boolean) {
            // Обычный startService (НЕ foreground) — дальше всё сделает CallService
            val i = Intent(ctx, CallTrampolineService::class.java).apply {
                putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
                putExtra(CallActionReceiver.EXTRA_USERNAME, username)
                putExtra(CallActionReceiver.EXTRA_IS_VIDEO, isVideo)
                putExtra(CallActionReceiver.EXTRA_OPEN_UI, openUi)
            }
            ctx.startService(i)
        }
    }
}
