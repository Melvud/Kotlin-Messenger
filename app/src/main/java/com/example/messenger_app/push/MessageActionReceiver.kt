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
                    ensureConnected()
                    AppGraph.chatRepo.markChannelRead(cid)
                    // Cancel notification
                    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    notificationManager.cancel(cid.hashCode())
                }
            }
            ACTION_REPLY -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val replyText = remoteInput?.getCharSequence(KEY_TEXT_REPLY)?.toString()

                if (!replyText.isNullOrBlank()) {
                    scope.launch {
                        ensureConnected()
                        AppGraph.chatRepo.sendMessage(cid, replyText)
                        AppGraph.chatRepo.markChannelRead(cid)
                        
                        // Update notification to show "Replied" or cancel it
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                        notificationManager.cancel(cid.hashCode())
                    }
                }
            }
        }
    }

    private suspend fun ensureConnected() {
        if (io.getstream.chat.android.client.ChatClient.instance().clientState.user.value == null) {
            Log.d("MessageActionReceiver", "User not connected, connecting...")
            AppGraph.chatRepo.connectUser()
        }
    }

    companion object {
        const val ACTION_MARK_AS_READ = "com.example.messenger_app.ACTION_MARK_AS_READ"
        const val ACTION_REPLY = "com.example.messenger_app.ACTION_REPLY"
        const val KEY_TEXT_REPLY = "key_text_reply"
    }
}
