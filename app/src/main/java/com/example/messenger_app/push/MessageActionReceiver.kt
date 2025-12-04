package com.example.messenger_app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import android.util.Log
import com.example.messenger_app.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MessageActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cid = intent.getStringExtra("cid") ?: return
        val action = intent.action

        Log.d("MessageActionReceiver", "Received action: $action for cid: $cid")

        val scope = CoroutineScope(Dispatchers.IO)

        when (action) {
            ACTION_MARK_AS_READ -> {
                scope.launch {
                    try {
                        AppGraph.chatRepo.markAsRead(cid, "all") // Assuming "all" or specific logic
                        // Ideally we need the message ID, but for now let's just cancel notification
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        notificationManager.cancel(cid.hashCode())
                    } catch (e: Exception) {
                        Log.e("MessageActionReceiver", "Error marking as read", e)
                    }
                }
            }
            ACTION_REPLY -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()

                if (!replyText.isNullOrBlank()) {
                    scope.launch {
                        try {
                            // We need a message object. Creating a simple text message.
                            // Note: We don't have senderId/Name here easily without querying repo or passing in intent.
                            // For simplicity, we'll try to use current user from Repo if available.
                            val currentUser = AppGraph.userRepo.currentUser()
                            if (currentUser != null) {
                                val message = com.example.messenger_app.data.model.Message(
                                    senderId = currentUser.uid,
                                    senderName = currentUser.displayName ?: "Me",
                                    encryptedContent = replyText,
                                    type = com.example.messenger_app.data.model.MessageType.TEXT,
                                    timestamp = System.currentTimeMillis()
                                )
                                // We need contactId (receiverId) to send push. 
                                // This is tricky from just a notification action without extra data.
                                // However, sendMessage in Repo might handle it if we pass a dummy or if we fetch it.
                                // Let's try to fetch chat participants or just send to chat if Repo supports it.
                                // Current Repo sendMessage requires contactId for Push.
                                // We can try to find the other user in the chat.
                                
                                // WORKAROUND: For now, we might fail to send push if we don't have contactId.
                                // But the message will be saved to Firestore.
                                AppGraph.chatRepo.sendMessage(cid, message, "") 
                                
                                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                notificationManager.cancel(cid.hashCode())
                            }
                        } catch (e: Exception) {
                            Log.e("MessageActionReceiver", "Error replying", e)
                        }
                    }
                }
            }
        }
    }

    private suspend fun ensureConnected() {
        /*
        if (io.getstream.chat.android.client.ChatClient.instance().clientState.user.value == null) {
            Log.d("MessageActionReceiver", "User not connected, connecting...")
            AppGraph.chatRepo.connectUser()
        }
        */
    }

    companion object {
        const val ACTION_MARK_AS_READ = "com.example.messenger_app.ACTION_MARK_AS_READ"
        const val ACTION_REPLY = "com.example.messenger_app.ACTION_REPLY"
        const val KEY_TEXT_REPLY = "key_text_reply"
    }
}
