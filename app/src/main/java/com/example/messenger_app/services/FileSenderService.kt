package com.example.messenger_app.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.messenger_app.AppGraph
import com.example.messenger_app.MainActivity
import com.example.messenger_app.R
import com.example.messenger_app.data.model.Message
import com.example.messenger_app.data.model.MessageType
import com.example.messenger_app.data.model.TransferStatus
import kotlinx.coroutines.launch
import java.io.File

class FileSenderService : LifecycleService() {

    companion object {
        const val ACTION_START_TRANSFER = "ACTION_START_TRANSFER"
        const val EXTRA_FILE_PATH = "EXTRA_FILE_PATH"
        const val EXTRA_CHAT_ID = "EXTRA_CHAT_ID"
        const val EXTRA_RECEIVER_TOKEN = "EXTRA_RECEIVER_TOKEN"
        const val EXTRA_RECEIVER_ID = "EXTRA_RECEIVER_ID"

        const val CHANNEL_ID = "file_transfer_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_START_TRANSFER) {
            val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
            val chatId = intent.getStringExtra(EXTRA_CHAT_ID)
            val receiverToken = intent.getStringExtra(EXTRA_RECEIVER_TOKEN)
            val receiverId = intent.getStringExtra(EXTRA_RECEIVER_ID)

            if (filePath != null && chatId != null && receiverToken != null && receiverId != null) {
                startForegroundService(filePath)
                startTransfer(filePath, chatId, receiverToken, receiverId)
            } else {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundService(filePath: String) {
        createNotificationChannel()

        val fileName = File(filePath).name
        val notification = createNotification("Раздача файла...", "Ожидание получателя: $fileName")
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this icon exists or use android default
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfer",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private var timeoutJob: kotlinx.coroutines.Job? = null

    private fun startTransfer(filePath: String, chatId: String, receiverToken: String, receiverId: String) {
        val file = File(filePath)
        val currentUser = AppGraph.userRepo.currentUser() ?: return

        // 1. Start WebRTC Transfer
        val transferId = AppGraph.fileTransferManager.startTransfer(
            chatId = chatId,
            file = file,
            receiverId = receiverId,
            senderId = currentUser.uid
        )

        // 2. Send Message (Offer) to Chat & Push
        lifecycleScope.launch {
            try {
                val message = Message(
                    senderId = currentUser.uid,
                    senderName = currentUser.displayName ?: "Unknown",
                    encryptedContent = "$transferId|${file.name}|${file.length()}", // Transfer ID|Name|Size
                    type = MessageType.FILE_OFFER,
                    timestamp = System.currentTimeMillis()
                )
                // This sends Push automatically via ChatRepository
                AppGraph.chatRepo.sendMessage(chatId, message, receiverId)
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }

        // 3. Start Timeout Timer (10 minutes)
        timeoutJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(10 * 60 * 1000L) // 10 minutes
            if (AppGraph.fileTransferManager.transferStatus.value == TransferStatus.PENDING || 
                AppGraph.fileTransferManager.transferStatus.value == TransferStatus.CONNECTING) {
                updateNotification("Никто не скачал файл", "Время ожидания истекло")
                AppGraph.fileTransferManager.cancelTransfer()
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }

        // 4. Observe Status
        lifecycleScope.launch {
            AppGraph.fileTransferManager.transferStatus.collect { status ->
                when (status) {
                    TransferStatus.TRANSFERRING -> {
                        timeoutJob?.cancel() // Cancel timeout once transfer starts
                    }
                    TransferStatus.COMPLETED -> {
                        timeoutJob?.cancel()
                        updateNotification("Файл отправлен", "Передача завершена")
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                    TransferStatus.FAILED -> {
                        timeoutJob?.cancel()
                        updateNotification("Ошибка отправки", "Не удалось передать файл")
                        stopForeground(STOP_FOREGROUND_DETACH)
                        stopSelf()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(title, content))
    }
}
