package com.example.messenger_app.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ServerTimestamp

/**
 * МОДЕЛИ ДАННЫХ ДЛЯ ЧАТА
 * Поддержка текста, изображений, видео, файлов, стикеров, голосовых сообщений
 */

// Типы сообщений
enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    FILE,
    VOICE,
    STICKER,
    LOCATION
}

// Статус сообщения
enum class MessageStatus {
    SENDING,    // Отправляется
    SENT,       // Отправлено
    DELIVERED,  // Доставлено
    READ,       // Прочитано
    FAILED      // Ошибка
}

// Модель сообщения
data class Message(
    val id: String = "",
    val chatId: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val senderPhotoUrl: String? = null,
    val type: MessageType = MessageType.TEXT,
    val content: String = "",           // Текст или URL медиафайла
    val thumbnailUrl: String? = null,   // Превью для видео/изображений
    val fileName: String? = null,       // Имя файла
    val fileSize: Long? = null,         // Размер файла в байтах
    val duration: Int? = null,          // Длительность аудио/видео в секундах
    val width: Int? = null,             // Ширина изображения/видео
    val height: Int? = null,            // Высота изображения/видео
    val replyToId: String? = null,      // ID сообщения, на которое отвечают
    val replyToText: String? = null,    // Текст сообщения, на которое отвечают
    val isEdited: Boolean = false,
    val status: MessageStatus = MessageStatus.SENDING,
    @ServerTimestamp
    val timestamp: Timestamp? = null,
    val localTimestamp: Long = System.currentTimeMillis()
)

// Модель чата
data class Chat(
    val id: String = "",
    val participants: List<String> = emptyList(),  // UIDs участников
    val participantNames: Map<String, String> = emptyMap(), // UID -> Имя
    val participantPhotos: Map<String, String?> = emptyMap(), // UID -> URL фото
    val lastMessage: String = "",
    val lastMessageTime: Timestamp? = null,
    val lastMessageSenderId: String = "",
    val unreadCount: Map<String, Int> = emptyMap(), // UID -> количество непрочитанных
    val typingUsers: List<String> = emptyList(),    // UIDs печатающих пользователей
    @ServerTimestamp
    val createdAt: Timestamp? = null,
    @ServerTimestamp
    val updatedAt: Timestamp? = null
)

// Информация о медиафайле для загрузки
data class MediaUpload(
    val localUri: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val type: MessageType
)

// Расширение для конвертации из Firestore
fun DocumentSnapshot.toMessage(): Message? {
    return try {
        val typeStr = getString("type") ?: "TEXT"
        val statusStr = getString("status") ?: "SENT"
        
        Message(
            id = id,
            chatId = getString("chatId") ?: "",
            senderId = getString("senderId") ?: "",
            senderName = getString("senderName") ?: "",
            senderPhotoUrl = getString("senderPhotoUrl"),
            type = MessageType.valueOf(typeStr),
            content = getString("content") ?: "",
            thumbnailUrl = getString("thumbnailUrl"),
            fileName = getString("fileName"),
            fileSize = getLong("fileSize"),
            duration = getLong("duration")?.toInt(),
            width = getLong("width")?.toInt(),
            height = getLong("height")?.toInt(),
            replyToId = getString("replyToId"),
            replyToText = getString("replyToText"),
            isEdited = getBoolean("isEdited") ?: false,
            status = MessageStatus.valueOf(statusStr),
            timestamp = getTimestamp("timestamp"),
            localTimestamp = getLong("localTimestamp") ?: System.currentTimeMillis()
        )
    } catch (e: Exception) {
        null
    }
}

fun DocumentSnapshot.toChat(): Chat? {
    return try {
        Chat(
            id = id,
            participants = get("participants") as? List<String> ?: emptyList(),
            participantNames = get("participantNames") as? Map<String, String> ?: emptyMap(),
            participantPhotos = get("participantPhotos") as? Map<String, String?> ?: emptyMap(),
            lastMessage = getString("lastMessage") ?: "",
            lastMessageTime = getTimestamp("lastMessageTime"),
            lastMessageSenderId = getString("lastMessageSenderId") ?: "",
            unreadCount = get("unreadCount") as? Map<String, Int> ?: emptyMap(),
            typingUsers = get("typingUsers") as? List<String> ?: emptyList(),
            createdAt = getTimestamp("createdAt"),
            updatedAt = getTimestamp("updatedAt")
        )
    } catch (e: Exception) {
        null
    }
}
