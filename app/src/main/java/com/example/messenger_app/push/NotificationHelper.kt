package com.example.messenger_app.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.messenger_app.MainActivity
import com.example.messenger_app.R

object NotificationHelper {

    private const val CHANNEL_INCOMING_CALLS = "incoming_calls_v2"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val calls = NotificationChannel(
                CHANNEL_INCOMING_CALLS,
                context.getString(R.string.notif_channel_calls),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_calls_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 900, 600, 900, 600, 1200)
                setSound(ringtoneUri, attrs)
                enableLights(true)
                lightColor = Color.GREEN
            }
            mgr.createNotificationChannel(calls)
        }
    }

    fun cancelIncomingCall(context: Context, callId: String) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(callId))
    }

    fun notificationIdFor(callId: String) = callId.hashCode()

    /**
     * Показ входящего звонка (без лишних «обычных» звуков).
     * Кнопки действий передают корректный тип: "audio" | "video".
     */
    fun showIncomingCall(
        context: Context,
        callId: String,
        callType: String,        // "audio" | "video"
        fromUsername: String
    ) {
        ensureChannels(context)
        val notifId = notificationIdFor(callId)

        // Тап по карточке — просто открыть приложение (без автопринятия)
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("source", "notification_tap")
            putExtra("callId", callId)
            putExtra("type", callType)
        }
        val contentPi = PendingIntent.getActivity(
            context,
            (notifId xor 0x77aa).and(0x7fffffff),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Отклонить
        val declineIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("action", "decline")
            putExtra("callId", callId)
            putExtra("type", callType)
            putExtra("fromUsername", fromUsername)
            putExtra("source", "notification_action")
        }
        val declinePi = PendingIntent.getActivity(
            context,
            (notifId xor 0x22bb).and(0x7fffffff),
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Принять
        val acceptIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("action", "accept")
            putExtra("callId", callId)
            putExtra("type", callType)
            putExtra("fromUsername", fromUsername)
            putExtra("source", "notification_action")
        }
        val acceptPi = PendingIntent.getActivity(
            context,
            (notifId xor 0x11aa).and(0x7fffffff),
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when (callType.lowercase()) {
            "video" -> context.getString(R.string.incoming_video_call_title)
            else -> context.getString(R.string.incoming_audio_call_title)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_INCOMING_CALLS)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText(fromUsername)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(contentPi)
            .setFullScreenIntent(contentPi, true)
            .addAction(R.drawable.ic_decline, context.getString(R.string.call_decline), declinePi)
            .addAction(R.drawable.ic_accept, context.getString(R.string.call_accept), acceptPi)

        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }
}
