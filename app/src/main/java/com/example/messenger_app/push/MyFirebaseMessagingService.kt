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
import androidx.core.app.Person
import com.example.messenger_app.MainActivity
import com.example.messenger_app.R
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.example.messenger_app.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {



    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            AppGraph.fcmTokenManager.registerToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        Log.d("FCM", "data=$d, notification=${message.notification}")

        val type = d["type"] ?: return

        when (type) {
            "call" -> handleCallNotification(d)
            "message", "message.new", "message_new" -> handleMessageNotification(message)
            "hangup" -> handleHangupNotification(d)
            "call_timeout" -> handleCallTimeout(d)
            "video_upgrade_request" -> handleVideoUpgradeRequest(d)
            "test_push" -> handleTestPush(d)
            else -> {
                // Fallback: if it has a cid or channel_id, treat as message
                if (d.containsKey("cid") || d.containsKey("channel_id")) {
                    handleMessageNotification(message)
                } else {
                    Log.w("FCM", "Unknown notification type: $type")
                }
            }
        }
    }

    /**
     * ИСПРАВЛЕНО: Не показываем уведомление если звонок от нас самих
     */
    private fun handleCallNotification(data: Map<String, String>) {
        val callId = data["callId"] ?: data["id"] ?: return
        val rawType = (data["callType"] ?: data["type"] ?: "audio").lowercase()
        val isVideo = rawType == "video" || data["isVideo"] == "true"
        val fromUsername = data["fromUsername"] ?: data["fromName"] ?: data["caller"] ?: ""
        val fromUserId = data["fromUserId"] ?: ""
        val toUserId = data["toUserId"] ?: ""

        // ИСПРАВЛЕНИЕ: проверяем, что уведомление для нас
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid

        if (currentUserId.isNullOrBlank()) {
            Log.w("FCM", "User not authenticated, ignoring call notification")
            return
        }

        // КРИТИЧЕСКАЯ ПРОВЕРКА: показываем уведомление только если мы получатель
        if (currentUserId != toUserId) {
            Log.w("FCM", "Ignoring call notification: not for us (to=$toUserId, me=$currentUserId)")
            return
        }

        // Дополнительная проверка: не показываем если звонок от нас
        if (currentUserId == fromUserId) {
            Log.w("FCM", "Ignoring call notification: from self")
            return
        }

        Log.d("FCM", "✅ Incoming call: from=$fromUsername ($fromUserId), video=$isVideo")

        NotificationHelper.showIncomingCall(
            ctx = applicationContext,
            callId = callId,
            username = fromUsername,
            isVideo = isVideo
        )
    }

    private fun handleMessageNotification(message: RemoteMessage) {
        val data = message.data
        // Stream Chat fields
        val chatId = data["cid"] ?: data["channel_id"] ?: data["chatId"] ?: return
        val senderId = data["sender_id"] ?: data["senderId"] ?: return
        val senderName = data["sender_name"] ?: data["senderName"] ?: "Пользователь"
        val messageType = data["type"] ?: "TEXT"
        val text = data["text"] ?: data["content"] ?: "Новое сообщение"

        val title = message.notification?.title ?: senderName
        val body = message.notification?.body ?: text

        Log.d("FCM", "Message notification received: chatId=$chatId, sender=$senderName, type=$messageType, text=$text")

        showMessageNotification(
            chatId = chatId,
            senderId = senderId,
            senderName = title,
            messageText = body,
            messageType = messageType
        )
    }

    private fun handleHangupNotification(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        
        // Check if we have an active incoming call notification (meaning we haven't answered or declined yet)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var isRinging = false
        var username = "Собеседник"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val notificationId = NotificationHelper.notificationIdFor(callId)
            val activeNotification = nm.activeNotifications.find { it.id == notificationId }
            if (activeNotification != null) {
                isRinging = true
                val extras = activeNotification.notification.extras
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    username = text.toString()
                }
            }
        }

        val reason = data["reason"]

        if (isRinging && reason != "answered_on_another_device") {
            NotificationHelper.showMissedCall(applicationContext, callId, username)
        }

        NotificationHelper.cancelIncomingCall(applicationContext, callId)
        OngoingCallStore.clear(applicationContext)
    }

    private fun handleCallTimeout(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        
        // Check if we have an active incoming call notification
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var isRinging = false
        var username = "Собеседник"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val notificationId = NotificationHelper.notificationIdFor(callId)
            val activeNotification = nm.activeNotifications.find { it.id == notificationId }
            if (activeNotification != null) {
                isRinging = true
                val extras = activeNotification.notification.extras
                val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    username = text.toString()
                }
            }
        }

        if (isRinging) {
            NotificationHelper.showMissedCall(applicationContext, callId, username)
        }
        
        NotificationHelper.cancelIncomingCall(applicationContext, callId)
    }

    private fun handleVideoUpgradeRequest(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val fromUsername = data["fromUsername"] ?: "Собеседник"
        Log.d("FCM", "Video upgrade request from $fromUsername for call $callId")
    }

    private fun handleTestPush(data: Map<String, String>) {
        Log.d("FCM", "Test push received")
        ensureMessageChannel()
        
        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Test Push")
            .setContentText("FCM is working correctly!")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(9999, notificationBuilder.build())
    }

    private fun showMessageNotification(
        chatId: String,
        senderId: String,
        senderName: String,
        messageText: String,
        messageType: String
    ) {
        // Don't show notification if user is currently in this chat
        if (com.example.messenger_app.AppState.activeChatId.value == chatId) {
            Log.d("FCM", "User is in chat $chatId, suppressing notification")
            return
        }

        ensureMessageChannel()

        val notificationId = chatId.hashCode()

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
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

        // Mark as Read Action
        val markReadIntent = Intent(this, MessageActionReceiver::class.java).apply {
            action = MessageActionReceiver.ACTION_MARK_AS_READ
            putExtra("cid", chatId)
        }
        val markReadPendingIntent = PendingIntent.getBroadcast(
            this,
            notificationId,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Reply Action
        val replyIntent = Intent(this, MessageActionReceiver::class.java).apply {
            action = MessageActionReceiver.ACTION_REPLY
            putExtra("cid", chatId)
        }
        val remoteInput = androidx.core.app.RemoteInput.Builder(MessageActionReceiver.KEY_TEXT_REPLY)
            .setLabel("Ответить")
            .build()

        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            notificationId + 1,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification,
            "Ответить",
            replyPendingIntent
        ).addRemoteInput(remoteInput)
         .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
         .setShowsUserInterface(true)
         .build()

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        // Person "Me" (current user)
        val me = Person.Builder()
            .setName("Вы") // Or get actual name if possible, but "You" is fine for self
            .setKey(FirebaseAuth.getInstance().currentUser?.uid ?: "me")
            .build()

        // Person "Sender"
        val senderPerson = Person.Builder()
            .setName(senderName)
            .setKey(senderId)
            .build()

        val messagingStyle = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(senderName)
            .addMessage(
                messageText,
                System.currentTimeMillis(),
                senderPerson
            )

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(messagingStyle)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setColor(0x2AABEE)
            .setGroup(MESSAGES_GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    "Прочитать",
                    markReadPendingIntent
                ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build()
            )
            .addAction(replyAction)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notificationBuilder.build())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val summaryNotification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setStyle(
                    NotificationCompat.InboxStyle()
                        .setBigContentTitle("Новые сообщения")
                )
                .setGroup(MESSAGES_GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(SUMMARY_ID, summaryNotification)
        }
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
                enableLights(true)
                lightColor = 0x2AABEE
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_MESSAGES = "messages"
        private const val MESSAGES_GROUP = "com.example.messenger_app.MESSAGES"
        private const val SUMMARY_ID = 0
    }
}