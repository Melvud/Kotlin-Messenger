package com.example.messenger_app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID

/**
 * РЕПОЗИТОРИЙ ДЛЯ РАБОТЫ С ЧАТАМИ
 * Полная поддержка текстовых сообщений, медиафайлов, стикеров
 */
class ChatRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage
) {
    
    private fun requireUid(): String = auth.currentUser?.uid 
        ?: error("User not authenticated")

    /**
     * Получить или создать чат с пользователем
     */
    suspend fun getOrCreateChat(otherUserId: String): String {
        val myUid = requireUid()
        
        // Ищем существующий чат между этими двумя пользователями
        val existingChats = db.collection("chats")
            .whereArrayContains("participants", myUid)
            .get()
            .await()
        
        val existingChat = existingChats.documents.find { doc ->
            val participants = doc.get("participants") as? List<*> ?: emptyList<String>()
            participants.contains(otherUserId) && participants.size == 2
        }
        
        if (existingChat != null) {
            return existingChat.id
        }
        
        // Создаем новый чат
        val myProfile = db.collection("users").document(myUid).get().await()
        val otherProfile = db.collection("users").document(otherUserId).get().await()
        
        val myName = myProfile.getString("username") ?: myProfile.getString("name") ?: "User"
        val otherName = otherProfile.getString("username") ?: otherProfile.getString("name") ?: "User"
        
        val myPhoto = myProfile.getString("photoUrl")
        val otherPhoto = otherProfile.getString("photoUrl")
        
        val chatData = hashMapOf(
            "participants" to listOf(myUid, otherUserId),
            "participantNames" to mapOf(myUid to myName, otherUserId to otherName),
            "participantPhotos" to mapOf(myUid to myPhoto, otherUserId to otherPhoto),
            "lastMessage" to "",
            "lastMessageTime" to FieldValue.serverTimestamp(),
            "lastMessageSenderId" to "",
            "unreadCount" to mapOf(myUid to 0, otherUserId to 0),
            "typingUsers" to emptyList<String>(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        
        val newChat = db.collection("chats").add(chatData).await()
        return newChat.id
    }

    /**
     * Получить поток всех чатов пользователя
     */
    fun getChatsFlow(): Flow<List<Chat>> = callbackFlow {
        val myUid = requireUid()
        
        val registration = db.collection("chats")
            .whereArrayContains("participants", myUid)
            .orderBy("lastMessageTime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ChatRepository", "Error listening to chats", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                
                val chats = snapshot?.documents?.mapNotNull { it.toChat() } ?: emptyList()
                trySend(chats)
            }
        
        awaitClose { registration.remove() }
    }

    /**
     * Получить поток сообщений чата
     */
    fun getMessagesFlow(chatId: String): Flow<List<Message>> = callbackFlow {
        val registration = db.collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("localTimestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("ChatRepository", "Error listening to messages", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                
                val messages = snapshot?.documents?.mapNotNull { it.toMessage() } ?: emptyList()
                trySend(messages)
            }
        
        awaitClose { registration.remove() }
    }

    /**
     * Отправить текстовое сообщение
     */
    suspend fun sendTextMessage(
        chatId: String,
        text: String,
        replyToId: String? = null,
        replyToText: String? = null
    ): String {
        val myUid = requireUid()
        val myProfile = db.collection("users").document(myUid).get().await()
        val myName = myProfile.getString("username") ?: myProfile.getString("name") ?: "User"
        val myPhoto = myProfile.getString("photoUrl")
        
        val messageId = UUID.randomUUID().toString()
        val messageData = hashMapOf(
            "chatId" to chatId,
            "senderId" to myUid,
            "senderName" to myName,
            "senderPhotoUrl" to myPhoto,
            "type" to MessageType.TEXT.name,
            "content" to text,
            "status" to MessageStatus.SENT.name,
            "isEdited" to false,
            "localTimestamp" to System.currentTimeMillis(),
            "timestamp" to FieldValue.serverTimestamp()
        )
        
        if (replyToId != null) {
            messageData["replyToId"] = replyToId
            messageData["replyToText"] = replyToText ?: ""
        }
        
        // Добавляем сообщение
        db.collection("chats")
            .document(chatId)
            .collection("messages")
            .document(messageId)
            .set(messageData)
            .await()
        
        // Обновляем последнее сообщение в чате
        updateChatLastMessage(chatId, text, myUid)
        
        return messageId
    }

    /**
     * Загрузить медиафайл и отправить сообщение
     */
    suspend fun sendMediaMessage(
        context: Context,
        chatId: String,
        mediaUpload: MediaUpload,
        caption: String? = null
    ): String {
        val myUid = requireUid()
        val myProfile = db.collection("users").document(myUid).get().await()
        val myName = myProfile.getString("username") ?: myProfile.getString("name") ?: "User"
        val myPhoto = myProfile.getString("photoUrl")
        
        // Загружаем файл в Firebase Storage
        val fileExtension = mediaUpload.fileName.substringAfterLast(".", "")
        val storagePath = "chats/$chatId/${UUID.randomUUID()}.$fileExtension"
        val storageRef = storage.reference.child(storagePath)
        
        val uploadTask = storageRef.putFile(Uri.parse(mediaUpload.localUri)).await()
        val downloadUrl = storageRef.downloadUrl.await().toString()
        
        // Создаем сообщение
        val messageId = UUID.randomUUID().toString()
        val messageData = hashMapOf(
            "chatId" to chatId,
            "senderId" to myUid,
            "senderName" to myName,
            "senderPhotoUrl" to myPhoto,
            "type" to mediaUpload.type.name,
            "content" to downloadUrl,
            "fileName" to mediaUpload.fileName,
            "fileSize" to mediaUpload.fileSize,
            "status" to MessageStatus.SENT.name,
            "isEdited" to false,
            "localTimestamp" to System.currentTimeMillis(),
            "timestamp" to FieldValue.serverTimestamp()
        )
        
        if (caption != null && caption.isNotBlank()) {
            messageData["caption"] = caption
        }
        
        db.collection("chats")
            .document(chatId)
            .collection("messages")
            .document(messageId)
            .set(messageData)
            .await()
        
        // Обновляем последнее сообщение
        val lastMessageText = when (mediaUpload.type) {
            MessageType.IMAGE -> "📷 Фото"
            MessageType.VIDEO -> "🎥 Видео"
            MessageType.FILE -> "📎 Файл"
            MessageType.VOICE -> "🎤 Голосовое сообщение"
            MessageType.STICKER -> "Стикер"
            else -> "Медиафайл"
        }
        updateChatLastMessage(chatId, lastMessageText, myUid)
        
        return messageId
    }

    /**
     * Отправить стикер
     */
    suspend fun sendSticker(chatId: String, stickerUrl: String): String {
        val myUid = requireUid()
        val myProfile = db.collection("users").document(myUid).get().await()
        val myName = myProfile.getString("username") ?: myProfile.getString("name") ?: "User"
        val myPhoto = myProfile.getString("photoUrl")
        
        val messageId = UUID.randomUUID().toString()
        val messageData = hashMapOf(
            "chatId" to chatId,
            "senderId" to myUid,
            "senderName" to myName,
            "senderPhotoUrl" to myPhoto,
            "type" to MessageType.STICKER.name,
            "content" to stickerUrl,
            "status" to MessageStatus.SENT.name,
            "isEdited" to false,
            "localTimestamp" to System.currentTimeMillis(),
            "timestamp" to FieldValue.serverTimestamp()
        )
        
        db.collection("chats")
            .document(chatId)
            .collection("messages")
            .document(messageId)
            .set(messageData)
            .await()
        
        updateChatLastMessage(chatId, "Стикер", myUid)
        
        return messageId
    }

    /**
     * Пометить сообщения как прочитанные
     */
    suspend fun markMessagesAsRead(chatId: String, messageIds: List<String>) {
        val batch = db.batch()
        
        messageIds.forEach { messageId ->
            val messageRef = db.collection("chats")
                .document(chatId)
                .collection("messages")
                .document(messageId)
            batch.update(messageRef, "status", MessageStatus.READ.name)
        }
        
        batch.commit().await()
        
        // Обнуляем счетчик непрочитанных для текущего пользователя
        val myUid = requireUid()
        db.collection("chats")
            .document(chatId)
            .update("unreadCount.$myUid", 0)
            .await()
    }

    /**
     * Удалить сообщение
     */
    suspend fun deleteMessage(chatId: String, messageId: String) {
        db.collection("chats")
            .document(chatId)
            .collection("messages")
            .document(messageId)
            .delete()
            .await()
    }

    /**
     * Редактировать сообщение
     */
    suspend fun editMessage(chatId: String, messageId: String, newText: String) {
        db.collection("chats")
            .document(chatId)
            .collection("messages")
            .document(messageId)
            .update(
                mapOf(
                    "content" to newText,
                    "isEdited" to true
                )
            )
            .await()
    }

    /**
     * Установить статус "печатает"
     */
    suspend fun setTyping(chatId: String, isTyping: Boolean) {
        val myUid = requireUid()
        
        if (isTyping) {
            db.collection("chats")
                .document(chatId)
                .update("typingUsers", FieldValue.arrayUnion(myUid))
                .await()
        } else {
            db.collection("chats")
                .document(chatId)
                .update("typingUsers", FieldValue.arrayRemove(myUid))
                .await()
        }
    }

    /**
     * Обновить последнее сообщение в чате
     */
    private suspend fun updateChatLastMessage(chatId: String, message: String, senderId: String) {
        db.collection("chats")
            .document(chatId)
            .update(
                mapOf(
                    "lastMessage" to message,
                    "lastMessageTime" to FieldValue.serverTimestamp(),
                    "lastMessageSenderId" to senderId,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            .await()
        
        // Увеличиваем счетчик непрочитанных для других участников
        val chat = db.collection("chats").document(chatId).get().await()
        val participants = chat.get("participants") as? List<String> ?: emptyList()
        val otherParticipants = participants.filter { it != senderId }
        
        otherParticipants.forEach { participantId ->
            db.collection("chats")
                .document(chatId)
                .update("unreadCount.$participantId", FieldValue.increment(1))
                .await()
        }
    }

    /**
     * Получить информацию о чате
     */
    suspend fun getChat(chatId: String): Chat? {
        val doc = db.collection("chats").document(chatId).get().await()
        return doc.toChat()
    }

    /**
     * Удалить чат
     */
    suspend fun deleteChat(chatId: String) {
        // Удаляем все сообщения
        val messages = db.collection("chats")
            .document(chatId)
            .collection("messages")
            .get()
            .await()
        
        val batch = db.batch()
        messages.documents.forEach { doc ->
            batch.delete(doc.reference)
        }
        batch.commit().await()
        
        // Удаляем сам чат
        db.collection("chats").document(chatId).delete().await()
    }
}
