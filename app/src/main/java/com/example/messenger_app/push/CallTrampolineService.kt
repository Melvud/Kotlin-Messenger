package com.example.messenger_app.push

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.messenger_app.MainActivity

class CallTrampolineService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra("callId")
        val type = intent?.getStringExtra("type") ?: "audio"
        if (callId != null) {
            val i = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("action", "accept")
                putExtra("callId", callId)
                putExtra("type", type)
            }
            startActivity(i)
        }
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
