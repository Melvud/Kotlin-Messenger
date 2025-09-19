package com.example.messenger_app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.messenger_app.MainActivity
import com.example.messenger_app.push.NotificationHelper

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra("callId") ?: return
        val type = intent.getStringExtra("type") ?: "audio"

        when (intent.action) {
            "com.example.messenger_app.ACTION_CALL_ACCEPT" -> {
                Log.d("CallActionReceiver", "ACTION_CALL_ACCEPT for callId=$callId")
                NotificationHelper.cancelIncomingCall(context, callId)

                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("action", "accept")
                    putExtra("callId", callId)
                    putExtra("type", type) // важно!
                }
                context.startActivity(activityIntent)
            }
            "com.example.messenger_app.ACTION_CALL_DECLINE" -> {
                Log.d("CallActionReceiver", "ACTION_CALL_DECLINE for callId=$callId")
                NotificationHelper.cancelIncomingCall(context, callId)

                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("action", "decline")
                    putExtra("callId", callId)
                    putExtra("type", type)
                }
                context.startActivity(activityIntent)
            }
        }
    }
}
