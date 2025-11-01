@file:Suppress("DEPRECATION")

package com.example.messenger_app.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.messenger_app.MainActivity
import com.example.messenger_app.R
import kotlin.math.abs

object NotificationHelper {

    const val INCOMING_CALL_CHANNEL_ID = "incoming_calls"
    const val ONGOING_CALL_CHANNEL_ID = "ongoing_calls"
    const val TRAMPOLINE_NOTIFICATION_ID = 1002 // New

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val incoming = NotificationChannel(
            INCOMING_CALL_CHANNEL_ID,
            "Incoming calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Ringing and incoming call alerts"
            setShowBadge(false)
            enableVibration(true)
            val uri = Uri.parse("android.resource://${ctx.packageName}/${R.raw.ringing}")
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(uri, attrs)
        }

        val ongoing = NotificationChannel(
            ONGOING_CALL_CHANNEL_ID,
            "Ongoing calls",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground call status"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        nm.createNotificationChannel(incoming)
        nm.createNotificationChannel(ongoing)
    }

    // New function
    fun createBaseNotification(
        context: Context,
        channelId: String,
        title: String,
        text: String,
        ongoing: Boolean = false
    ): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .build()
    }

    /** Heads-up полноэкранное уведомление для входящего звонка. */
    fun showIncomingCall(
        ctx: Context,
        callId: String,
        username: String,
        isVideo: Boolean
    ) {
        ensureChannels(ctx)

        // Полноэкранный интент: просто открываем приложение с нужными deeplink_*,
        // дальше MainActivity сам навигирует на CallScreen (роль "callee").
        val fullScreen = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("deeplink_callId", callId)
            putExtra("deeplink_isVideo", isVideo)
            putExtra("deeplink_username", username)
        }
        val fullScreenPi = PendingIntent.getActivity(
            ctx,
            (notificationIdFor(callId) shl 1),
            fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Кнопки Accept/Decline остаются через BroadcastReceiver
        val acceptPi = actionPi(ctx, CallActionReceiver.ACTION_INCOMING_ACCEPT, callId, username, isVideo)
        val declinePi = actionPi(ctx, CallActionReceiver.ACTION_INCOMING_DECLINE, callId, username, isVideo)

        val n = NotificationCompat.Builder(ctx, INCOMING_CALL_CHANNEL_ID) // Use new constant
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(if (isVideo) "Видео-звонок" else "Входящий звонок")
            .setContentText(username.ifBlank { "Звонок" })
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPi, true)
            .addAction(android.R.drawable.ic_menu_call, "Принять", acceptPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отклонить", declinePi)
            .build().apply { flags = flags or Notification.FLAG_INSISTENT }

        NotificationManagerCompat.from(ctx).notify(notificationIdFor(callId), n)
    }

    fun cancelIncomingCall(ctx: Context, callId: String) {
        NotificationManagerCompat.from(ctx).cancel(notificationIdFor(callId))
    }

    internal fun notificationIdFor(callId: String): Int = 0x5550000 + abs(callId.hashCode())

    private fun actionPi(
        ctx: Context,
        action: String,
        callId: String,
        username: String,
        isVideo: Boolean
    ): PendingIntent {
        val i = Intent(ctx, CallActionReceiver::class.java).apply {
            this.action = action
            putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
            putExtra(CallActionReceiver.EXTRA_USERNAME, username)
            putExtra(CallActionReceiver.EXTRA_IS_VIDEO, isVideo)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(
            ctx,
            (notificationIdFor(callId) shl 3) xor action.hashCode(),
            i,
            flags
        )
    }
}