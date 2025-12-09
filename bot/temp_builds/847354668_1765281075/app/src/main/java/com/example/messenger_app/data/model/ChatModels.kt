package com.example.messenger_app.data.model

data class User(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String = "",
    val token: String = "",
    val publicKey: String? = null
)

enum class MessageType {
    TEXT, IMAGE, VIDEO, AUDIO, FILE,
    IMAGE_LINK, VIDEO_LINK, FILE_LINK,
    SYSTEM
}

data class Message(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val encryptedContent: String = "", // Stores text or file URL
    val type: MessageType = MessageType.TEXT,
    val timestamp: Long = System.currentTimeMillis(),
    val replyToId: String? = null, // ID of the message being replied to
    val replyPreview: String? = null, // Decrypted preview of the replied message (for UI)
    val reactions: Map<String, String> = emptyMap(), // userId -> emoji
    val replyTo: Message? = null,
    val deletedFor: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    @get:com.google.firebase.firestore.PropertyName("isRead")
    @set:com.google.firebase.firestore.PropertyName("isRead")
    var isRead: Boolean = false

    @get:com.google.firebase.firestore.Exclude
    @set:com.google.firebase.firestore.Exclude
    var isUploading: Boolean = false

    @get:com.google.firebase.firestore.Exclude
    @set:com.google.firebase.firestore.Exclude
    var localPath: String? = null

    @get:com.google.firebase.firestore.Exclude
    @set:com.google.firebase.firestore.Exclude
    var isError: Boolean = false
}

data class Chat(
    val id: String = "",
    val name: String = "",
    val lastMessage: String = "",
    @get:com.google.firebase.firestore.PropertyName("createdAt")
    @set:com.google.firebase.firestore.PropertyName("createdAt")
    var timestamp: java.util.Date? = null,
    val participants: List<String> = emptyList(),
    @get:com.google.firebase.firestore.PropertyName("isGroup")
    @set:com.google.firebase.firestore.PropertyName("isGroup")
    var isGroup: Boolean = false
)
