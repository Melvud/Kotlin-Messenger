package com.example.messenger_app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

class CallTrampolineService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("CallTrampolineService", "onStartCommand: $intent")
        showNotification()

        val callId = intent?.getStringExtra("callId")
        val action = intent?.getStringExtra("action")
        Log.d("CallTrampolineService", "Received callId=$callId, action=$action")

        if (callId != null && action != null) {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("callId", callId)
                putExtra("action", action)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.e("CallTrampolineService", "Failed to start MainActivity: ${e.message}")
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            stopSelf()
        }, 800)

        return START_NOT_STICKY
    }

    private fun showNotification() {
        val channelId = "call_trampoline"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Call Trampoline", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Звонок")
            .setContentText("Открытие экрана вызова...")
            .setSmallIcon(R.drawable.ic_call)
            .build()
        startForeground(9998, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}