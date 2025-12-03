package com.example.messenger_app.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.models.User
import com.google.firebase.messaging.FirebaseMessaging
import io.getstream.chat.android.models.Device
import io.getstream.chat.android.models.PushProvider
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ChatRepository(
    private val client: ChatClient,
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions
) {

    /**
     * Подключает пользователя к Stream Chat.
     * 1. Получает UID из Firebase.
     * 2. Вызывает Cloud Function `createStreamToken` для генерации безопасного токена.
     * 3. Подключает WebSocket к Stream.
     */
    suspend fun connectUser(): Boolean {
        val firebaseUser = auth.currentUser ?: return false
        
        // Если уже подключены - выходим, но обновляем токен пушей на всякий случай
        if (client.clientState.user.value != null) {
            registerPushDevice()
            return true
        }

        return try {
            Log.d("ChatRepo", "Connecting user: ${firebaseUser.uid}")
            
            // Force refresh token to ensure valid session
            try {
                firebaseUser.getIdToken(true).await()
            } catch (e: Exception) {
                Log.e("ChatRepo", "Failed to refresh token", e)
                return false
            }

            // Шаг 1: Запрашиваем токен у нашего бэкенда (Cloud Function)
            val token = fetchTokenFromBackend()
            
            // Шаг 2: Формируем объект пользователя
            // Используем имя из Firebase, если оно есть, иначе "User"
            val name = if (!firebaseUser.displayName.isNullOrBlank()) firebaseUser.displayName else "User"
            
            val user = User(
                id = firebaseUser.uid,
                name = name ?: "User",
                image = firebaseUser.photoUrl?.toString() ?: ""
            )

            // Шаг 3: Коннектимся
            val result = client.connectUser(user, token).await()
            
            if (result.isSuccess) {
                Log.d("ChatRepo", "Stream User Connected successfully")
                
                // Шаг 4: Регистрируем устройство для пушей
                registerPushDevice()
                
                // Шаг 5: Если имя было "User", а в Firebase оно нормальное - обновляем в Stream
                if (result.getOrNull()?.user?.name == "User" && !firebaseUser.displayName.isNullOrBlank()) {
                    updateStreamUser(firebaseUser.displayName!!, firebaseUser.photoUrl?.toString())
                }
                
                true
            } else {
                Log.e("ChatRepo", "Stream connect error: ${result.errorOrNull()}")
                false
            }
        } catch (e: Exception) {
            Log.e("ChatRepo", "Connection failed", e)
            false
        }
    }

    private suspend fun registerPushDevice() {
        try {
            val token = FirebaseMessaging.getInstance().token.await()
            client.addDevice(Device(token = token, pushProvider = PushProvider.FIREBASE, providerName = "firebase")).await()
            Log.d("ChatRepo", "Device registered for push: $token")
        } catch (e: Exception) {
            Log.e("ChatRepo", "Failed to register device for push", e)
        }
    }

    /**
     * Вызов Cloud Function 'createStreamToken'
     */
    private suspend fun fetchTokenFromBackend(): String = suspendCancellableCoroutine { cont ->
        functions
            .getHttpsCallable("createStreamToken")
            .call()
            .addOnSuccessListener { result ->
                val data = result.data as? Map<String, Any>
                val token = data?.get("token") as? String
                if (token != null) {
                    cont.resume(token)
                } else {
                    cont.resumeWithException(Exception("Token is null in response"))
                }
            }
            .addOnFailureListener { e ->
                cont.resumeWithException(e)
            }
    }

    /**
     * Создать личный чат (канал)
     */
    suspend fun createDirectChat(otherUserId: String): String? {
        val currentUserId = auth.currentUser?.uid ?: return null
        
        val result = client.createChannel(
            channelType = "messaging",
            channelId = "", // Авто-генерация ID для distinct channel
            memberIds = listOf(currentUserId, otherUserId),
            extraData = emptyMap()
        ).await()

        return result.getOrNull()?.cid
    }

    /**
     * Создать групповой чат
     */
    suspend fun createGroupChat(name: String, memberIds: List<String>): String? {
        val currentUserId = auth.currentUser?.uid ?: return null
        val allMembers = (memberIds + currentUserId).distinct()

        val result = client.createChannel(
            channelType = "messaging",
            channelId = "", // Авто-генерация ID
            memberIds = allMembers,
            extraData = mapOf(
                "name" to name,
                "image" to "https://ui-avatars.com/api/?name=$name&background=random" // Простая аватарка
            )
        ).await()

        return result.getOrNull()?.cid
    }

    fun disconnectUser() {
        client.disconnect(true).enqueue()
    }

    /**
     * Обновляет данные пользователя в Stream Chat (имя, аватар)
     */
    suspend fun updateStreamUser(name: String, photoUrl: String?) {
        val currentUser = client.clientState.user.value ?: return
        val updatedUser = currentUser.copy(
            name = name,
            image = photoUrl ?: ""
        )
        client.updateUser(updatedUser).await()
    }

    /**
     * Удалить канал (чат) навсегда
     */
    suspend fun deleteChannel(cid: String): Boolean {
        return try {
            val result = client.channel(cid).delete().await()
            if (result.isSuccess) {
                Log.d("ChatRepo", "Channel deleted successfully: $cid")
                true
            } else {
                Log.e("ChatRepo", "Failed to delete channel: ${result.errorOrNull()}")
                false
            }
        } catch (e: Exception) {
            Log.e("ChatRepo", "Error deleting channel", e)
            false
        }
    }

    suspend fun markChannelRead(cid: String) {
        try {
            client.channel(cid).markRead().await()
            Log.d("ChatRepo", "Channel marked as read: $cid")
        } catch (e: Exception) {
            Log.e("ChatRepo", "Failed to mark channel as read", e)
        }
    }

    suspend fun sendMessage(cid: String, text: String) {
        try {
            val message = io.getstream.chat.android.models.Message(text = text)
            client.channel(cid).sendMessage(message).await()
            Log.d("ChatRepo", "Message sent to $cid: $text")
        } catch (e: Exception) {
            Log.e("ChatRepo", "Failed to send message", e)
        }
    }
}