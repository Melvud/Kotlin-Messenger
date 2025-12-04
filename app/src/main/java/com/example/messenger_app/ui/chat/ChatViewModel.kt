package com.example.messenger_app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.messenger_app.data.model.Message
import com.example.messenger_app.data.model.MessageType
import com.example.messenger_app.data.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val chatId: String,
    private val currentUserId: String,
    private val currentUserName: String,
    private val contactId: String, // Added contactId
    private val targetUserToken: String,
    private val fileTransferManager: com.example.messenger_app.data.p2p.FileTransferManager,
    private val callsRepository: com.example.messenger_app.data.CallsRepository
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _replyToMessage = MutableStateFlow<Message?>(null)
    val replyToMessage: StateFlow<Message?> = _replyToMessage.asStateFlow()

    private val _targetToken = MutableStateFlow(targetUserToken)
    val targetToken: StateFlow<String> = _targetToken.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _navigateToCall = MutableStateFlow<com.example.messenger_app.data.CallInfo?>(null)
    val navigateToCall: StateFlow<com.example.messenger_app.data.CallInfo?> = _navigateToCall.asStateFlow()

    fun onCallNavigated() {
        _navigateToCall.value = null
    }

    fun initiateCall(isVideo: Boolean) {
        viewModelScope.launch {
            try {
                val type = if (isVideo) "video" else "audio"
                val callInfo = callsRepository.startCall(contactId, type)
                _navigateToCall.value = callInfo
            } catch (e: Exception) {
                _error.value = "Ошибка звонка: ${e.message}"
            }
        }
    }

    init {
        loadMessages()
        if (targetUserToken.isBlank() && currentUserId.isNotBlank()) {
            fetchTargetUserToken()
        }
        observeUserPresence()
        observeTypingStatus()
    }

    private fun fetchTargetUserToken() {
        if (contactId.isBlank()) return
        
        viewModelScope.launch {
            try {
                val devices = chatRepository.firestore.collection("users")
                    .document(contactId)
                    .collection("devices")
                    .orderBy("updatedAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
                    .await()
                
                val token = devices.documents.firstOrNull()?.getString("token")
                if (!token.isNullOrBlank()) {
                    _targetToken.value = token
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error fetching token", e)
            }
        }
    }

    private fun observeUserPresence() {
        if (contactId.isBlank()) return

        val docRef = chatRepository.firestore.collection("users").document(contactId)
        docRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.e("ChatViewModel", "Listen failed.", e)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val isOnline = snapshot.getBoolean("isOnline") ?: false
                _isOnline.value = isOnline
            } else {
                _isOnline.value = false
            }
        }
    }

    private val _isOtherUserTyping = MutableStateFlow(false)
    val isOtherUserTyping: StateFlow<Boolean> = _isOtherUserTyping.asStateFlow()

    private var typingJob: kotlinx.coroutines.Job? = null

    private fun observeTypingStatus() {
        viewModelScope.launch {
            chatRepository.getTypingStatusFlow(chatId, currentUserId).collect {
                _isOtherUserTyping.value = it
            }
        }
    }

    fun onTyping(text: String) {
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            chatRepository.setTypingStatus(chatId, currentUserId, true)
            kotlinx.coroutines.delay(3000) // Stop typing after 3 seconds of inactivity
            chatRepository.setTypingStatus(chatId, currentUserId, false)
        }
    }

    private fun loadMessages() {
        viewModelScope.launch {
            _loading.value = true
            chatRepository.getMessagesFlow(chatId)
                .catch { e ->
                    _error.value = "Ошибка загрузки: ${e.message}"
                    _loading.value = false
                }
                .collect { msgList ->
                    _messages.value = msgList
                    _loading.value = false
                    // Mark unread messages as read
                    msgList.filter { !it.isRead && it.senderId != currentUserId }.forEach { msg ->
                        chatRepository.markAsRead(chatId, msg.id)
                    }
                }
        }
    }

    fun setReplyToMessage(message: Message) {
        _replyToMessage.value = message
    }

    fun clearReply() {
        _replyToMessage.value = null
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            try {
                val replyMsg = _replyToMessage.value
                val message = Message(
                    senderId = currentUserId,
                    senderName = currentUserName,
                    encryptedContent = text, // Will be encrypted in repo
                    type = MessageType.TEXT,
                    timestamp = System.currentTimeMillis(),
                    replyToId = replyMsg?.id,
                    replyPreview = replyMsg?.let { "${it.senderName}: ${getPreviewContent(it)}" }
                )
                // Use contactId to fetch tokens in repo
                chatRepository.sendMessage(chatId, message, contactId)
                clearReply()
            } catch (e: com.example.messenger_app.utils.ConfigMissingException) {
                _error.value = "Ошибка конфигурации: Ключ не найден. Звонки и Пуши не работают."
            } catch (e: Exception) {
                _error.value = "Ошибка отправки: ${e.message}"
            }
        }
    }

    fun uploadMedia(uri: android.net.Uri, type: MessageType) {
        viewModelScope.launch {
            _loading.value = true
            try {
                val folder = when (type) {
                    MessageType.IMAGE -> "images"
                    MessageType.VIDEO -> "videos"
                    MessageType.AUDIO -> "audio"
                    MessageType.FILE -> "files"
                    else -> "others"
                }
                val downloadUrl = chatRepository.uploadFile(uri, folder)
                
                val replyMsg = _replyToMessage.value
                val message = Message(
                    senderId = currentUserId,
                    senderName = currentUserName,
                    encryptedContent = downloadUrl, // Store URL as content
                    type = type,
                    timestamp = System.currentTimeMillis(),
                    replyToId = replyMsg?.id,
                    replyPreview = replyMsg?.let { "${it.senderName}: ${getPreviewContent(it)}" }
                )
                chatRepository.sendMessage(chatId, message, contactId)
                clearReply()
            } catch (e: com.example.messenger_app.utils.ConfigMissingException) {
                _error.value = "Ошибка конфигурации: Ключ не найден. Звонки и Пуши не работают."
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки медиа: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                chatRepository.deleteMessage(chatId, messageId)
            } catch (e: Exception) {
                _error.value = "Ошибка удаления: ${e.message}"
            }
        }
    }

    fun addReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            try {
                chatRepository.addReaction(chatId, messageId, currentUserId, emoji)
            } catch (e: Exception) {
                _error.value = "Ошибка реакции: ${e.message}"
            }
        }
    }

    // P2P Transfer
    val transferStatus = fileTransferManager.transferStatus
    val transferProgress = fileTransferManager.progress
    val activeTransferId = fileTransferManager.currentTransferId

    fun downloadFile(message: Message) {
        if (message.type != MessageType.FILE_OFFER) return
        
        // Format: transferId|fileName|fileSize
        val parts = message.encryptedContent.split("|")
        if (parts.size < 3) {
            _error.value = "Неверный формат сообщения"
            return
        }
        
        val transferId = parts[0]
        val fileName = parts[1]
        val fileSize = parts[2].toLongOrNull() ?: 0L

        viewModelScope.launch {
            try {
                fileTransferManager.receiveTransfer(chatId, transferId, fileName, fileSize)
            } catch (e: Exception) {
                _error.value = "Ошибка скачивания: ${e.message}"
            }
        }
    }

    private fun getPreviewContent(message: Message): String {
        return when (message.type) {
            MessageType.TEXT -> message.encryptedContent
            MessageType.IMAGE -> "Фото"
            MessageType.VIDEO -> "Видео"
            MessageType.AUDIO -> "Голосовое сообщение"
            MessageType.FILE -> "Файл"
            MessageType.FILE_OFFER -> "Предложение файла"
        }
    }

    fun clearError() {
        _error.value = null
    }
}
