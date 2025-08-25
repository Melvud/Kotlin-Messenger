package com.example.messenger_app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ID = "calls_channel"

    fun showCallNotification(
        context: Context,
        callId: String,
        callerName: String,
        callType: String,
        notificationId: Int
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Создаём канал с кастомным звуком (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = Uri.parse("android.resource://${context.packageName}/raw/ringing")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            channel.description = "Incoming call alerts"
            channel.setSound(
                soundUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            channel.enableVibration(true)
            channel.vibrationPattern = longArrayOf(0, 700, 400, 700, 400, 1000)
            channel.lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            notificationManager.createNotificationChannel(channel)
        }

        // --- INTENTS ---

        // "Принять" - запускает MainActivity с action "accept"
        val acceptIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("callId", callId)
            putExtra("action", "accept")
            putExtra("notificationId", notificationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val acceptPendingIntent = PendingIntent.getActivity(
            context,
            notificationId + 1,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Отклонить" - запускает MainActivity с action "decline"
        val declineIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("callId", callId)
            putExtra("action", "decline")
            putExtra("notificationId", notificationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val declinePendingIntent = PendingIntent.getActivity(
            context,
            notificationId + 2,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Тап по уведомлению — тоже "accept"
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("callId", callId)
            putExtra("action", "accept")
            putExtra("notificationId", notificationId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId + 3,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Входящий звонок")
            .setContentText("От $callerName")
            .setSmallIcon(R.drawable.ic_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVibrate(longArrayOf(0, 700, 400, 700, 400, 1000))
            .setSound(Uri.parse("android.resource://${context.packageName}/raw/ringing"))
            .setContentIntent(contentPendingIntent) // Тап по уведомлению == принять
            .setFullScreenIntent(contentPendingIntent, true)
            // Порядок: сначала "Отклонить", потом "Принять"
            .addAction(R.drawable.ic_decline, "Отклонить", declinePendingIntent)
            .addAction(R.drawable.ic_accept, "Принять", acceptPendingIntent)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}