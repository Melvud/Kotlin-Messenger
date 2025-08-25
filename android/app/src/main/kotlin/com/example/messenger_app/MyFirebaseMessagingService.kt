package com.example.messenger_app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d("FCM", "Message received: ${remoteMessage.data}")

        val data = remoteMessage.data
        val type = data["type"]

        if (type == "call") {
            val callId = data["callId"] ?: return
            val callerName = data["fromUsername"] ?: "Неизвестный"
            val callType = data["callType"] ?: "audio"
            val notificationId = System.currentTimeMillis().toInt()

            NotificationHelper.showCallNotification(
                applicationContext,
                callId,
                callerName,
                callType,
                notificationId
            )
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "New token: $token")
        // тут можно отправить токен на сервер
    }
}