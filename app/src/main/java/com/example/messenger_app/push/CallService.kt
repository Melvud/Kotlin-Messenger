package com.example.messenger_app.push

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.messenger_app.MainActivity
import com.example.messenger_app.R
import com.example.messenger_app.webrtc.WebRtcCallManager
import kotlin.math.abs

/**
 * Foreground-сервис звонка: держит уведомление, может проигрывать ringback.
 * УЛУЧШЕНО: Добавлена возможность инициализации соединения при принятии звонка
 */
@Suppress("DEPRECATION")
class CallService : Service() {

    private var callId: String = ""
    private var username: String = ""
    private var isVideo: Boolean = false

    private var ringback: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRingbackSafe()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_STOP_RINGBACK -> {
                stopRingbackSafe()
                return START_NOT_STICKY
            }

            else -> {
                // Параметры старта/рестарта сервиса
                callId = intent?.getStringExtra(EXTRA_CALL_ID) ?: callId
                username = intent?.getStringExtra(EXTRA_USERNAME) ?: username
                isVideo = intent?.getBooleanExtra(EXTRA_IS_VIDEO, isVideo) ?: isVideo
                val openUi = intent?.getBooleanExtra(EXTRA_OPEN_UI, false) ?: false
                val playRingback = intent?.getBooleanExtra(EXTRA_PLAY_RINGBACK, false) ?: false
                val initConnection = intent?.getBooleanExtra(EXTRA_INIT_CONNECTION, false) ?: false

                // Строим и выставляем foreground-уведомление
                val notif = buildOngoingNotification(this, callId, username, isVideo)
                startForeground(ongoingNotificationId(callId), notif)

                // ИСПРАВЛЕНИЕ 1: Если нужно инициализировать соединение (при принятии звонка)
                if (initConnection) {
                    WebRtcCallManager.init(applicationContext)
                    WebRtcCallManager.startCall(
                        callId = callId,
                        isVideo = isVideo,
                        playRingback = false,
                        role = "callee" // принимающий звонок
                    )
                }

                // Открыть UI по требованию
                if (openUi) {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            putExtra("deeplink_callId", callId)
                            putExtra("deeplink_isVideo", isVideo)
                            putExtra("deeplink_username", username)
                        }
                    )
                }

                if (playRingback) startRingbackSafe()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRingbackSafe()
        super.onDestroy()
    }

    /* ================= RINGBACK ================= */

    private fun startRingbackSafe() {
        if (ringback != null) return
        ringback = MediaPlayer.create(
            this,
            R.raw.ringback,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            0
        ).apply {
            isLooping = true
            start()
        }
    }

    private fun stopRingbackSafe() {
        try { ringback?.stop() } catch (_: Throwable) {}
        try { ringback?.release() } catch (_: Throwable) {}
        ringback = null
    }

    /* ================= NOTIFICATION ================= */

    private fun buildOngoingNotification(
        ctx: Context,
        callId: String,
        username: String,
        isVideo: Boolean
    ): Notification {
        val contentIntent = PendingIntentFactory.activity(
            ctx = ctx,
            requestCode = 300 + abs(callId.hashCode()),
            intent = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("deeplink_callId", callId)
                putExtra("deeplink_isVideo", isVideo)
                putExtra("deeplink_username", username)
            },
            mutable = true
        )

        val title = if (isVideo) "Видео-звонок" else "Звонок"
        val text = if (username.isNotBlank()) "В разговоре с $username" else "Идёт звонок"

        return NotificationCompat.Builder(ctx, NotificationHelper.CHANNEL_ONGOING_CALLS)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun ongoingNotificationId(callId: String): Int = 0x6600000 + abs(callId.hashCode())

    companion object {
        private const val ACTION_STOP = "CallService.ACTION_STOP"
        private const val ACTION_STOP_RINGBACK = "CallService.ACTION_STOP_RINGBACK"

        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_IS_VIDEO = "isVideo"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_OPEN_UI = "openUi"
        const val EXTRA_PLAY_RINGBACK = "playRingback"
        const val EXTRA_INIT_CONNECTION = "initConnection"

        fun start(
            ctx: Context,
            callId: String,
            username: String,
            isVideo: Boolean,
            openUi: Boolean = false,
            playRingback: Boolean = false,
            initializeConnection: Boolean = false
        ) {
            NotificationHelper.ensureChannels(ctx)
            val i = Intent(ctx, CallService::class.java).apply {
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_IS_VIDEO, isVideo)
                putExtra(EXTRA_OPEN_UI, openUi)
                putExtra(EXTRA_PLAY_RINGBACK, playRingback)
                putExtra(EXTRA_INIT_CONNECTION, initializeConnection)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, CallService::class.java).apply { action = ACTION_STOP }
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stopRingback(ctx: Context) {
            val i = Intent(ctx, CallService::class.java).apply { action = ACTION_STOP_RINGBACK }
            ctx.startService(i)
        }
    }
}

/** Утилита для PendingIntent без ворнингов по флагам. */
private object PendingIntentFactory {
    fun activity(
        ctx: Context,
        requestCode: Int,
        intent: Intent,
        mutable: Boolean = false
    ): android.app.PendingIntent {
        val flags =
            (if (mutable) android.app.PendingIntent.FLAG_MUTABLE else android.app.PendingIntent.FLAG_IMMUTABLE) or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
        return android.app.PendingIntent.getActivity(ctx, requestCode, intent, flags)
    }
}