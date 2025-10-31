package com.example.messenger_app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.messenger_app.MainActivity
import com.example.messenger_app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        FcmTokenManager.onNewToken(applicationContext, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        Log.d("FCM", "data=$d, notification=${message.notification}")

        val type = d["type"] ?: return

        when (type) {
            "call" -> handleCallNotification(d)
            "message" -> handleMessageNotification(message)
            "hangup" -> handleHangupNotification(d)
            "call_timeout" -> handleCallTimeout(d)
            "video_upgrade_request" -> handleVideoUpgradeRequest(d)
            else -> Log.w("FCM", "Unknown notification type: $type")
        }
    }

    private fun handleCallNotification(data: Map<String, String>) {
        val callId = data["callId"] ?: data["id"] ?: return
        val rawType = (data["callType"] ?: data["type"] ?: "audio").lowercase()
        val isVideo = rawType == "video" || data["isVideo"] == "true"
        val fromUsername = data["fromUsername"] ?: data["fromName"] ?: data["caller"] ?: ""

        NotificationHelper.showIncomingCall(
            ctx = applicationContext,
            callId = callId,
            username = fromUsername,
            isVideo = isVideo
        )
    }

    private fun handleMessageNotification(message: RemoteMessage) {
        val data = message.data
        val chatId = data["chatId"] ?: return
        val senderId = data["senderId"] ?: return
        val senderName = data["senderName"] ?: "Пользователь"

        // Используем notification payload если есть, иначе из data
        val title = message.notification?.title ?: senderName
        val body = message.notification?.body ?: data["content"] ?: "Новое сообщение"

        Log.d("FCM", "Message notification: chatId=$chatId, sender=$senderName")

        showMessageNotification(
            chatId = chatId,
            senderId = senderId,
            senderName = title,
            messageText = body
        )
    }

    private fun handleHangupNotification(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        NotificationHelper.cancelIncomingCall(applicationContext, callId)
        OngoingCallStore.clear(applicationContext)
    }

    private fun handleCallTimeout(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        NotificationHelper.cancelIncomingCall(applicationContext, callId)
    }

    private fun handleVideoUpgradeRequest(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val fromUsername = data["fromUsername"] ?: "Собеседник"
        // Это будет обработано WebRtcCallManager через Firestore listener
        Log.d("FCM", "Video upgrade request from $fromUsername for call $callId")
    }

    private fun showMessageNotification(
        chatId: String,
        senderId: String,
        senderName: String,
        messageText: String
    ) {
        ensureMessageChannel()

        val notificationId = chatId.hashCode()

        // Intent для открытия чата
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("action", "open_chat")
            putExtra("chatId", chatId)
            putExtra("otherUserId", senderId)
            putExtra("otherUserName", senderName)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        PendingIntent.FLAG_IMMUTABLE
                    else 0)
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification) // Убедитесь что этот drawable существует
            .setContentTitle(senderName)
            .setContentText(messageText)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    private fun ensureMessageChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = CHANNEL_MESSAGES
            val channelName = "Сообщения"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = "Уведомления о новых сообщениях"
                enableVibration(true)
                setShowBadge(true)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_MESSAGES = "messages"
    }
}