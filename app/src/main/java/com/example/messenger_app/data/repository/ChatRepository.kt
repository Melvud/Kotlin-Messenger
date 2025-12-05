package com.example.messenger_app.data.repository

import android.net.Uri
import android.util.Log
import com.example.messenger_app.data.model.Message
import com.example.messenger_app.data.model.MessageType
import com.example.messenger_app.data.push.PushRepository
import com.google.firebase.firestore.FieldValue
import com.example.messenger_app.utils.SecurityUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import android.content.Context

class ChatRepository(
    val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(), // Made public for ChatsListScreen
    private val pushRepository: PushRepository,
    private val context: Context,
    private val database: com.example.messenger_app.data.local.AppDatabase
) {

    suspend fun sendMessage(chatId: String, message: Message, recipientId: String) {
        try {
            // 1. Encrypt content
            val encryptedContent = SecurityUtils.encrypt(message.encryptedContent)
            val encryptedMessage = message.copy(encryptedContent = encryptedContent)

            // 2. Save to Firestore
            val messageId = if (message.id.isEmpty()) UUID.randomUUID().toString() else message.id
            val finalMessage = encryptedMessage.copy(id = messageId)

            firestore.collection("chats")
                .document(chatId)
                .collection("messages")
                .document(messageId)
                .set(finalMessage)
                .await()

            // 3. Update Chat Metadata
            val chatUpdate = mapOf(
                "lastMessage" to "Encrypted Message", // Don't expose content in plain text
                "timestamp" to message.timestamp
            )
            firestore.collection("chats").document(chatId).set(chatUpdate, SetOptions.merge()).await()

            // 4. Fetch Recipient Tokens & Send Push
            val tokensSnapshot = firestore.collection("users")
                .document(recipientId)
                .collection("devices")
                .get()
                .await()

            val tokens = tokensSnapshot.documents.mapNotNull { it.getString("token") }

            tokens.forEach { token ->
                pushRepository.sendDirectPush(
                    targetToken = token,
                    title = message.senderName,
                    body = if (message.type == com.example.messenger_app.data.model.MessageType.IMAGE) "📷 Фото" else "Новое сообщение",
                    data = mapOf(
                        "type" to "message_new",
                        "chatId" to chatId,
                        "senderId" to message.senderId,
                        "senderName" to message.senderName,
                        "messageId" to messageId
                    )
                )
            }

        } catch (e: Exception) {
            Log.e("ChatRepository", "Error sending message", e)
            throw e
        }
    }

    fun getMessagesFlow(chatId: String): Flow<List<Message>> = kotlinx.coroutines.flow.flow {
        val listener = firestore.collection("chats")
            .document(chatId)
            .collection("messages")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null) {
                    val messages = snapshot.documents.mapNotNull { doc ->
                        try {
                            val message = Message(
                                id = doc.id,
                                senderId = doc.getString("senderId") ?: "",
                                senderName = doc.getString("senderName") ?: "",
                                encryptedContent = doc.getString("encryptedContent") ?: "",
                                type = MessageType.valueOf(doc.getString("type") ?: "TEXT"),
                                timestamp = doc.getLong("timestamp") ?: 0L,
                                replyToId = doc.getString("replyToId"),
                                replyPreview = doc.getString("replyPreview"),
                                reactions = (doc.get("reactions") as? Map<String, String>) ?: emptyMap(),
                                deletedFor = (doc.get("deletedFor") as? List<String>) ?: emptyList(),
                                metadata = (doc.get("metadata") as? Map<String, String>) ?: emptyMap()
                            ).apply {
                                isRead = doc.getBoolean("isRead") ?: false
                            }
                            com.example.messenger_app.data.local.LocalMessage(
                                id = message.id,
                                chatId = chatId,
                                senderId = message.senderId,
                                senderName = message.senderName,
                                encryptedContent = message.encryptedContent,
                                type = message.type.name,
                                timestamp = message.timestamp,
                                replyToId = message.replyToId,
                                replyPreview = message.replyPreview,
                                reactions = message.reactions,
                                metadata = message.metadata,
                                isRead = message.isRead
                            )
                        } catch (e: Exception) {
                            Log.e("ChatRepository", "Error parsing message from Firestore", e)
                            null
                        }
                    }
                    // Insert into DB
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val currentIds = messages.map { it.id }
                        database.messageDao().deleteMessagesNotIn(chatId, currentIds)
                        database.messageDao().insertMessages(messages)
                    }
                }
            }

        try {
            emitAll(
                database.messageDao().getMessages(chatId).map { localList ->
                    localList.map { local ->
                        val decryptedContent = SecurityUtils.decrypt(local.encryptedContent)
                        val finalContent = if (decryptedContent.isEmpty() && local.encryptedContent.isNotEmpty()) {
                            "Ошибка шифрования"
                        } else {
                            decryptedContent
                        }
                        Message(
                            id = local.id,
                            senderId = local.senderId,
                            senderName = local.senderName,
                            encryptedContent = finalContent,
                            type = try { com.example.messenger_app.data.model.MessageType.valueOf(local.type) } catch(e:Exception) { com.example.messenger_app.data.model.MessageType.TEXT },
                            timestamp = local.timestamp,
                            replyToId = local.replyToId,
                            replyPreview = local.replyPreview,
                            reactions = local.reactions,
                            metadata = local.metadata
                        ).apply {
                            isRead = local.isRead
                        }
                    }
                }
            )
        } finally {
            listener.remove()
        }
    }

    fun getChatsFlow(userId: String): Flow<List<com.example.messenger_app.data.model.Chat>> = kotlinx.coroutines.flow.flow {
        val listener = firestore.collection("chats")
            .whereArrayContains("participants", userId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) return@addSnapshotListener
                if (snapshot != null) {
                    val chats = snapshot.documents.mapNotNull { doc ->
                        val chat = doc.toObject(com.example.messenger_app.data.model.Chat::class.java)
                        chat?.let {
                            com.example.messenger_app.data.local.LocalChat(
                                id = it.id,
                                name = it.name,
                                lastMessage = it.lastMessage,
                                timestamp = it.timestamp,
                                participants = it.participants,
                                isGroup = it.isGroup
                            )
                        }
                    }
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val currentIds = chats.map { it.id }
                        database.chatDao().deleteChatsNotIn(currentIds)
                        database.chatDao().insertChats(chats)
                    }
                }
            }
        
        try {
            emitAll(
                database.chatDao().getChats().map { localList ->
                    localList.map { local ->
                        com.example.messenger_app.data.model.Chat(
                            id = local.id,
                            name = local.name,
                            lastMessage = local.lastMessage,
                            timestamp = local.timestamp,
                            participants = local.participants,
                            isGroup = local.isGroup
                        )
                    }
                }
            )
        } finally {
            listener.remove()
        }
    }

    suspend fun markAsRead(chatId: String, messageId: String) {
        try {
            firestore.collection("chats")
                .document(chatId)
                .collection("messages")
                .document(messageId)
                .update("isRead", true)
                .await()
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error marking as read", e)
        }
    }

    suspend fun addReaction(chatId: String, messageId: String, userId: String, emoji: String?) {
        try {
            val messageRef = firestore.collection("chats")
                .document(chatId)
                .collection("messages")
                .document(messageId)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(messageRef)
                val message = snapshot.toObject(Message::class.java)
                if (message != null) {
                    val newReactions = message.reactions.toMutableMap()
                    if (emoji == null) {
                        newReactions.remove(userId)
                    } else {
                        newReactions[userId] = emoji
                    }
                    transaction.update(messageRef, "reactions", newReactions)
                }
            }.await()
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error adding/removing reaction", e)
        }
    }



    suspend fun deleteMessage(chatId: String, messageId: String) {
        try {
            firestore.collection("chats")
                .document(chatId)
                .collection("messages")
                .document(messageId)
                .delete()
                .await()
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error deleting message", e)
            throw e
        }
    }

    suspend fun deleteMessageForEveryone(chatId: String, messageId: String) {
        try {
            firestore.collection("chats")
                .document(chatId)
                .collection("messages")
                .document(messageId)
                .delete()
                .await()
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error deleting message for everyone", e)
        }
    }

    suspend fun deleteMessageForMe(chatId: String, messageId: String, userId: String) {
        try {
            val messageRef = firestore.collection("chats")
                .document(chatId)
                .collection("messages")
                .document(messageId)

            firestore.runTransaction { transaction ->
                val snapshot = transaction.get(messageRef)
                val message = snapshot.toObject(Message::class.java)
                if (message != null) {
                    val newDeletedFor = message.deletedFor.toMutableList()
                    if (!newDeletedFor.contains(userId)) {
                        newDeletedFor.add(userId)
                        transaction.update(messageRef, "deletedFor", newDeletedFor)
                    }
                }
            }.await()
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error deleting message for me", e)
        }
    }
    suspend fun createChat(participants: List<String>, isGroup: Boolean, groupName: String?): String {
        // 1. For 1-on-1, check if chat already exists
        if (!isGroup && participants.size == 2) {
            val existingChat = firestore.collection("chats")
                .whereEqualTo("isGroup", false)
                .whereArrayContains("participants", participants[0])
                .get()
                .await()
                .documents
                .find { doc ->
                    val docParticipants = doc.get("participants") as? List<*>
                    docParticipants?.containsAll(participants) == true
                }

            if (existingChat != null) {
                return existingChat.id
            }
        }

        // 2. Create new chat
        val chatId = UUID.randomUUID().toString()
        val chatData = hashMapOf(
            "id" to chatId,
            "participants" to participants,
            "isGroup" to isGroup,
            "createdAt" to FieldValue.serverTimestamp(),
            "lastMessage" to "",
            "timestamp" to System.currentTimeMillis()
        )

        if (isGroup && groupName != null) {
            chatData["name"] = groupName
        }

        firestore.collection("chats").document(chatId).set(chatData).await()
        return chatId
    }
    suspend fun setTypingStatus(chatId: String, userId: String, isTyping: Boolean) {
        try {
            val update = mapOf(
                "typing.$userId" to if (isTyping) System.currentTimeMillis() else null
            )
            firestore.collection("chats").document(chatId).update(update).await()
        } catch (e: Exception) {
            // Ignore errors for typing status
        }
    }

    fun getTypingStatusFlow(chatId: String, currentUserId: String): Flow<Boolean> = callbackFlow {
        val listener = firestore.collection("chats").document(chatId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val typingMap = snapshot.get("typing") as? Map<String, Any>
                    if (typingMap != null) {
                        val isAnyoneElseTyping = typingMap.entries.any { (userId, value) ->
                            val timestamp = (value as? Number)?.toLong() ?: 0L
                            userId != currentUserId && (System.currentTimeMillis() - timestamp) < 5000 // 5 seconds timeout
                        }
                        trySend(isAnyoneElseTyping)
                    } else {
                        trySend(false)
                    }
                } else {
                    trySend(false)
                }
            }
        awaitClose { listener.remove() }
    }

    suspend fun deleteChatForEveryone(chatId: String) {
        try {
            firestore.collection("chats").document(chatId).delete().await()
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error deleting chat for everyone", e)
            throw e
        }
    }

    suspend fun deleteChatForMe(chatId: String, userId: String) {
        try {
            firestore.collection("chats").document(chatId)
                .update("participants", FieldValue.arrayRemove(userId))
                .await()
        } catch (e: Exception) {
            Log.e("ChatRepository", "Error deleting chat for me", e)
            throw e
        }
    }
}
