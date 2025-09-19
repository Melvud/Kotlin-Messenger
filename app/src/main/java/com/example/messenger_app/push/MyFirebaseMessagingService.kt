package com.example.messenger_app.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Регистрируем токен в фоне, чтобы не ловить "suspend only from coroutine"
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FcmTokenManager.ensureCurrentTokenRegistered(applicationContext)
                Log.d(TAG, "FCM token registered/updated")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register FCM token", t)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data.isEmpty()) return

        val type = data["type"] ?: ""
        if (type.equals("call", ignoreCase = true)) {
            val callId = data["callId"]
            if (callId.isNullOrBlank()) {
                Log.w(TAG, "Incoming call push without callId")
                return
            }

            val callType = (data["callType"] ?: "audio").lowercase()
            val fromUsername = data["fromUsername"] ?: ""

            // Показ входящего вызова. ВАЖНО: используем текущую сигнатуру NotificationHelper
            // с callType и fromUsername (без параметра isVideo), чтобы не было ошибок компиляции.
            try {
                NotificationHelper.showIncomingCall(
                    context = applicationContext,
                    callId = callId,
                    callType = callType,
                    fromUsername = fromUsername
                )
                Log.d(TAG, "Incoming call notification shown: id=$callId, type=$callType, from=$fromUsername")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to show incoming call notification", t)
            }
        } else {
            Log.d(TAG, "Push type is not 'call': $type")
        }
    }

    companion object {
        private const val TAG = "MyFcmService"
    }
}
