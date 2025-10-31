package com.example.messenger_app.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        FcmTokenManager.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        Log.d("FCM", "data=$d")

        // Универсальный парсинг
        val callId = d["callId"] ?: d["id"] ?: return
        val rawType = (d["callType"] ?: d["type"] ?: "audio").lowercase()
        val isVideo = rawType == "video" || d["isVideo"] == "true"
        val fromUsername = d["fromUsername"] ?: d["fromName"] ?: d["caller"] ?: ""

        // Пуш «входящий звонок» (актуальная сигнатура)
        NotificationHelper.showIncomingCall(
            ctx = applicationContext,
            callId = callId,
            username = fromUsername,
            isVideo = isVideo
        )
    }
}
