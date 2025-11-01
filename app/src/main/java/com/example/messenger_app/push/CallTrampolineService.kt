package com.example.messenger_app.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallTrampolineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
                putExtra("callId", callId)
                putExtra("username", username)
                putExtra("isVideo", isVideo)
                putExtra("openUi", openUi)
            }
            ctx.startService(intent)
            Log.d(TAG, "✅ Trampoline service started for callId=$callId")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Trampoline onStartCommand")

        intent?.let {
            val callId = it.getStringExtra("callId") ?: return@let
            val username = it.getStringExtra("username") ?: "User"
            val isVideo = it.getBooleanExtra("isVideo", false)
            val openUi = it.getBooleanExtra("openUi", false)

            Log.d(TAG, "-> Starting main CallService from trampoline")

            // Запускаем основной CallService
            CallService.start(
                ctx = applicationContext,
                callId = callId,
                username = username,
                isVideo = isVideo,
                openUi = false, // CallService не открывает UI
                playRingback = false
            )

            // ✅ ИСПРАВЛЕНО: Открываем CallScreen через MainActivity
            if (openUi) {
                Log.d(TAG, "-> Opening CallScreen UI")
                val mainIntent = Intent(applicationContext, com.example.messenger_app.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("deeplink_callId", callId)
                    putExtra("deeplink_isVideo", isVideo)
                    putExtra("deeplink_username", username)
                    putExtra("deeplink_playRingback", false) // callee не играет рингбэк
                }
                startActivity(mainIntent)
            }

            // Закрываем трамплин через короткую задержку
            scope.launch {
                delay(500)
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }
}