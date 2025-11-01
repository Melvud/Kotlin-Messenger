package com.example.messenger_app.push

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.messenger_app.R

class CallService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var currentCallId: String? = null

    companion object {
        private const val TAG = "CallService"
        private const val CHANNEL_ID = "ongoing_call_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(
            ctx: Context,
            callId: String,
            username: String,
            isVideo: Boolean,
            openUi: Boolean = false,
            playRingback: Boolean = false
        ) {
            val intent = Intent(ctx, CallService::class.java).apply {
                putExtra("callId", callId)
                putExtra("username", username)
                putExtra("isVideo", isVideo)
                putExtra("openUi", openUi)
                putExtra("playRingback", playRingback)
            }

            try {
                ContextCompat.startForegroundService(ctx, intent)
                Log.d(TAG, "✅ Service start requested for callId=$callId")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start service", e)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, CallService::class.java))
                Log.d(TAG, "✅ Service stop requested")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to stop service", e)
            }
        }

        fun stopRingback(ctx: Context) {
            try {
                val intent = Intent(ctx, CallService::class.java).apply {
                    action = "STOP_RINGBACK"
                }
                ctx.startService(intent)
                Log.d(TAG, "✅ Stop ringback requested")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to stop ringback", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "📱 Service onCreate()")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📱 Service onStartCommand: action=${intent?.action}")

        // ✅ КРИТИЧЕСКИ ВАЖНО: НЕМЕДЛЕННО ВЫЗЫВАЕМ startForeground()
        if (intent?.action == null || intent.action != "STOP_RINGBACK") {
            val callId = intent?.getStringExtra("callId") ?: currentCallId ?: "unknown"
            val username = intent?.getStringExtra("username") ?: "Звонок"
            val isVideo = intent?.getBooleanExtra("isVideo", false) ?: false

            val notification = buildNotification(callId, username, isVideo)
            startForeground(NOTIFICATION_ID, notification)
        }

        when (intent?.action) {
            "STOP_RINGBACK" -> {
                stopRingback()
                return START_NOT_STICKY
            }
            else -> {
                handleCallStart(intent)
            }
        }

        return START_STICKY
    }

    private fun handleCallStart(intent: Intent?) {
        val callId = intent?.getStringExtra("callId") ?: return
        val username = intent.getStringExtra("username") ?: "Собеседник"
        val isVideo = intent.getBooleanExtra("isVideo", false)
        val playRingback = intent.getBooleanExtra("playRingback", false)

        currentCallId = callId

        OngoingCallStore.save(this, callId, isVideo, username)

        Log.d(TAG, """
            ════════════════════════════════════
            📞 CALL SERVICE STARTED
            callId: $callId
            username: $username
            isVideo: $isVideo
            playRingback: $playRingback
            ════════════════════════════════════
        """.trimIndent())

        if (playRingback) {
            startRingbackTone()
        }
    }

    private fun startRingbackTone() {
        try {
            stopRingback()

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build()
                )

                setDataSource(this@CallService, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                isLooping = true
                prepare()
                start()
            }

            Log.d(TAG, "🔊 Ringback tone started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start ringback", e)
        }
    }

    private fun stopRingback() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
            Log.d(TAG, "🔇 Ringback stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringback", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "📱 Service onDestroy()")
        stopRingback()
        OngoingCallStore.clear(this)

        // ✅ БЕЗОПАСНОЕ УДАЛЕНИЕ FOREGROUND
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping foreground", e)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ════════════════════════════════════════════════════════════
    //                     NOTIFICATION
    // ════════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Активный звонок",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления для активных звонков"
                setSound(null, null)
                enableVibration(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            Log.d(TAG, "✅ Notification channel created")
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildNotification(callId: String, username: String, isVideo: Boolean): Notification {
        val hangupIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_HANGUP
            putExtra("callId", callId)
        }
        val hangupPending = PendingIntent.getBroadcast(
            this, 1, hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_TOGGLE_MUTE
        }
        val mutePending = PendingIntent.getBroadcast(
            this, 2, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speakerIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_TOGGLE_SPEAKER
        }
        val speakerPending = PendingIntent.getBroadcast(
            this, 3, speakerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isVideo) "Видеозвонок" else "Звонок")
            .setContentText(username)
            .setSmallIcon(R.drawable.ic_call)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(null)
            .setVibrate(null)
            .addAction(R.drawable.ic_mic, "Микрофон", mutePending)
            .addAction(R.drawable.ic_speaker, "Динамик", speakerPending)
            .addAction(R.drawable.ic_hangup, "Завершить", hangupPending)
            .setFullScreenIntent(hangupPending, true)
            .build()
    }
}