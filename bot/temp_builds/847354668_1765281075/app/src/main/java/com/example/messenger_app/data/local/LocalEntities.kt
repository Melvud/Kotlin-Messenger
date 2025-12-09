package com.example.messenger_app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class LocalMessage(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val encryptedContent: String,
    val type: String,
    val timestamp: Long,
    val replyToId: String?,
    val replyPreview: String?,
    val reactions: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
    val isRead: Boolean
)

@Entity(tableName = "chats")
data class LocalChat(
    @PrimaryKey val id: String,
    val name: String,
    val lastMessage: String,
    val timestamp: Long,
    val participants: List<String>,
    val isGroup: Boolean
)
