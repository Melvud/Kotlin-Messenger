package com.example.messenger_app.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.messenger_app.AppGraph
import com.example.messenger_app.MainActivity
import com.example.messenger_app.R
import com.example.messenger_app.data.model.Message
import com.example.messenger_app.data.model.MessageType
import com.example.messenger_app.data.p2p.TransferState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class FileRelayService : LifecycleService() {

    companion object {
        const val ACTION_START_HOSTING = "ACTION_START_HOSTING"
        const val ACTION_STOP_HOSTING = "ACTION_STOP_HOSTING"
        
        const val EXTRA_FILE_URI = "EXTRA_FILE_URI"
        const val EXTRA_CHAT_ID = "EXTRA_CHAT_ID"
        const val EXTRA_RECEIVER_ID = "EXTRA_RECEIVER_ID"

        const val CHANNEL_ID = "file_relay_channel"
        const val NOTIFICATION_ID = 2001
    }

    private var timeoutJob: Job? = null
    private var transferJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START_HOSTING -> {
                val fileUriString = intent.getStringExtra(EXTRA_FILE_URI)
                val chatId = intent.getStringExtra(EXTRA_CHAT_ID)
                val receiverId = intent.getStringExtra(EXTRA_RECEIVER_ID)

                if (fileUriString != null && chatId != null && receiverId != null) {
                    val fileUri = Uri.parse(fileUriString)
                    startHosting(fileUri, chatId, receiverId)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP_HOSTING -> {
                stopHosting()
            }
        }

        return START_NOT_STICKY
    }

    private fun startHosting(fileUri: Uri, chatId: String, receiverId: String) {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Раздача файла...", "Ожидание подключения..."))

        val currentUser = AppGraph.userRepo.currentUser() ?: return

        // 1. Start Hosting via Manager
        transferJob = lifecycleScope.launch {
            AppGraph.fileTransferManager.startHosting(
                chatId = chatId,
                fileUri = fileUri,
                senderId = currentUser.uid,
                receiverId = receiverId
            ).collect { state ->
                when (state) {
                    is TransferState.Connecting -> {
                        // Send Offer Message to Chat (only once, ideally manager handles this but we need to notify receiver)
                        // Actually, manager creates the Firestore doc. We just need to tell receiver "Hey, look at this transferId"
                        // But wait, manager returns Flow, so we need to get the transferId.
                        // The manager exposes `currentTransferId`.
                        
                        // Wait a bit for transferId to be set
                        delay(500) 
                        val transferId = AppGraph.fileTransferManager.currentTransferId.value
                        if (transferId != null) {
                            sendOfferMessage(chatId, receiverId, transferId, fileUri)
                        }
                    }
                    is TransferState.Transferring -> {
                        timeoutJob?.cancel()
                        val progressPercent = (state.progress * 100).toInt()
                        updateNotification("Отправка файла...", "Прогресс: $progressPercent%")
                    }
                    is TransferState.Completed -> {
                        timeoutJob?.cancel()
                        updateNotification("Файл отправлен", "Успешно!")
                        delay(2000)
                        stopHosting()
                    }
                    is TransferState.Failed -> {
                        timeoutJob?.cancel()
                        updateNotification("Ошибка отправки", state.reason)
                        delay(3000)
                        stopHosting()
                    }
                    else -> {}
                }
            }
        }

        // 2. Start Timeout (10 mins)
        timeoutJob = lifecycleScope.launch {
            delay(10 * 60 * 1000L)
            updateNotification("Время истекло", "Никто не подключился")
            AppGraph.fileTransferManager.cancelTransfer()
            delay(2000)
            stopHosting()
        }
    }

    private suspend fun sendOfferMessage(chatId: String, receiverId: String, transferId: String, fileUri: Uri) {
        val currentUser = AppGraph.userRepo.currentUser() ?: return
        
        // Get filename and size
        var fileName = "file"
        var fileSize = 0L
        contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
            }
        }

        val message = Message(
            senderId = currentUser.uid,
            senderName = currentUser.displayName ?: "Unknown",
            encryptedContent = "$transferId|$fileName|$fileSize", // Protocol: ID|Name|Size
            type = MessageType.FILE_OFFER,
            timestamp = System.currentTimeMillis()
        )
        
        try {
            AppGraph.chatRepo.sendMessage(chatId, message, receiverId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopHosting() {
        transferJob?.cancel()
        timeoutJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(title, content))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Relay Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
