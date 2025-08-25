package com.example.messenger_app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ACCEPT = "com.example.messenger_app.ACTION_CALL_ACCEPT"
        const val ACTION_DECLINE = "com.example.messenger_app.ACTION_CALL_DECLINE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val callId = intent.getStringExtra("callId")
        val notificationId = intent.getIntExtra("notificationId", -1)

        Log.d("CallActionReceiver", "onReceive: action=$action, callId=$callId")

        // Скрыть уведомление
        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }

        if (callId.isNullOrEmpty()) return

        if (action == ACTION_ACCEPT) {
            // Получаем FCM токен и обновляем оба поля
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                            db.collection("calls").document(callId)
                                .update(
                                    mapOf(
                                        "calleeStatus" to "accepted",
                                        "activeDeviceToken" to token
                                    )
                                )
                        } catch (e: Exception) {
                            Log.e("CallActionReceiver", "Failed to update call: ${e.message}")
                        }
                    }
                } else {
                    Log.e("CallActionReceiver", "Failed to get FCM token: ${task.exception?.message}")
                }
            }
        }

        if (action == ACTION_ACCEPT || action == ACTION_DECLINE) {
            val serviceIntent = Intent(context, CallTrampolineService::class.java).apply {
                putExtra("callId", callId)
                putExtra("action", if (action == ACTION_ACCEPT) "accept" else "decline")
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("CallActionReceiver", "Failed to start trampoline: ${e.message}")
            }
        }
    }
}