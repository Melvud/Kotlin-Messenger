package com.example.messenger_app.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.messenger_app.data.model.Message
import com.example.messenger_app.data.model.MessageType
import com.example.messenger_app.data.repository.ChatRepository
import com.example.messenger_app.data.upload.EncryptedDownloadManager
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
    private val callsRepository: com.example.messenger_app.data.CallsRepository,
    private val encryptedUploadManager: com.example.messenger_app.data.upload.EncryptedUploadManager,
    private val encryptedDownloadManager: com.example.messenger_app.data.upload.EncryptedDownloadManager
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() {
        _error.value = null
    }

    private val _replyToMessage = MutableStateFlow<Message?>(null)
    val replyToMessage: StateFlow<Message?> = _replyToMessage.asStateFlow()

    private val _targetToken = MutableStateFlow(targetUserToken)
    val targetToken: StateFlow<String> = _targetToken.asStateFlow()

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _lastSeen = MutableStateFlow<Long?>(null)
    val lastSeen: StateFlow<Long?> = _lastSeen.asStateFlow()

    private val _navigateToCall = MutableStateFlow<com.example.messenger_app.data.CallInfo?>(null)
    val navigateToCall: StateFlow<com.example.messenger_app.data.CallInfo?> = _navigateToCall.asStateFlow()

    fun onCallNavigated() {
        _navigateToCall.value = null
    }

    fun initiateCall(isVideo: Boolean, onNavigate: (com.example.messenger_app.data.CallInfo) -> Unit) {
        viewModelScope.launch {
            try {
                val type = if (isVideo) "video" else "audio"
                val callInfo = callsRepository.startCall(contactId, type)
                onNavigate(callInfo)
            } catch (e: Exception) {
                _error.value = "Ошибка звонка: ${e.message}"
            }
        }
    }

    private val _isGroup = MutableStateFlow(false)
    val isGroup: StateFlow<Boolean> = _isGroup.asStateFlow()

    private val _participantsCount = MutableStateFlow(0)
    val participantsCount: StateFlow<Int> = _participantsCount.asStateFlow()

    init {
        loadMessages()
        if (targetUserToken.isBlank() && currentUserId.isNotBlank()) {
            fetchTargetUserToken()
        }
        observeUserPresence()
        observeTypingStatus()
        observeChatMetadata()
    }

    private fun observeChatMetadata() {
        chatRepository.firestore.collection("chats").document(chatId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    _isGroup.value = snapshot.getBoolean("isGroup") ?: false
                    val participants = snapshot.get("participants") as? List<*>
                    _participantsCount.value = participants?.size ?: 0
                }
            }
    }

    private fun observeUserPresence() {
        if (contactId.isBlank()) return
        chatRepository.firestore.collection("users").document(contactId)
            .addSnapshotListener { snapshot, e ->
                if (e != null) return@addSnapshotListener
                if (snapshot != null && snapshot.exists()) {
                    _isOnline.value = snapshot.getBoolean("isOnline") ?: false
                    _lastSeen.value = snapshot.getLong("lastSeen")
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

    private fun fetchTargetUserToken() {
        viewModelScope.launch {
            try {
                val snapshot = chatRepository.firestore.collection("users")
                    .document(contactId)
                    .collection("devices")
                    .get()
                    .await()
                val token = snapshot.documents.firstOrNull()?.getString("token")
                if (token != null) {
                    _targetToken.value = token
                }
            } catch (e: Exception) {
                // Ignore
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
                    val filteredMsgList = msgList.filter { message ->
                        !message.deletedFor.contains(currentUserId)
                    }
                    // Populate localPath for file messages
                    val updatedList = filteredMsgList.map { msg ->
                        if (msg.type == MessageType.IMAGE_LINK || msg.type == MessageType.VIDEO_LINK || msg.type == MessageType.FILE_LINK) {
                            val mimeType = msg.metadata["mimeType"]
                            val name = msg.metadata["name"]
                            val file = encryptedDownloadManager.getLocalFile(msg.encryptedContent, mimeType ?: "", name)
                            if (file != null) {
                                msg.copy().apply { localPath = file.absolutePath }
                            } else {
                                msg
                            }
                        } else {
                            msg
                        }
                    }
                    _messages.value = updatedList
                    _loading.value = false
                    
                    // Mark ALL unread messages as read immediately if we are in the chat
                    val hasUnread = updatedList.any { !it.isRead && it.senderId != currentUserId }
                    if (hasUnread) {
                        viewModelScope.launch {
                            try {
                                chatRepository.markAllAsRead(chatId)
                            } catch (e: Exception) {
                                Log.e("ChatViewModel", "Error marking all as read", e)
                            }
                        }
                    }
                        
                    // Auto-download if media and not local
                    updatedList.forEach { msg ->
                        if ((msg.type == MessageType.IMAGE_LINK || msg.type == MessageType.VIDEO_LINK) && msg.localPath == null) {
                             // Check if already downloading or failed? 
                             // For now, simple check.
                             if (_downloadStates[msg.id] !is EncryptedDownloadManager.DownloadState.Downloading && 
                                 _downloadStates[msg.id] !is EncryptedDownloadManager.DownloadState.Success) {
                                 startDownload(msg)
                             }
                        }
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

    private val _downloadStates = androidx.compose.runtime.mutableStateMapOf<String, com.example.messenger_app.data.upload.EncryptedDownloadManager.DownloadState>()
    val downloadStates: Map<String, com.example.messenger_app.data.upload.EncryptedDownloadManager.DownloadState> = _downloadStates

    private var downloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    fun startDownload(message: Message) {
        if (_downloadStates[message.id] is com.example.messenger_app.data.upload.EncryptedDownloadManager.DownloadState.Downloading) return

        val job = viewModelScope.launch {
            val mimeType = message.metadata["mimeType"] ?: "application/octet-stream"
            val name = message.metadata["name"]
            
            encryptedDownloadManager.downloadMedia(message.encryptedContent, mimeType, name)
                .collect { state ->
                    _downloadStates[message.id] = state
                    if (state is com.example.messenger_app.data.upload.EncryptedDownloadManager.DownloadState.Success) {
                        // Update localPath
                        val currentList = _messages.value.toMutableList()
                        val index = currentList.indexOfFirst { it.id == message.id }
                        if (index != -1) {
                            currentList[index] = currentList[index].copy().apply {
                                localPath = state.file.absolutePath
                            }
                            _messages.value = currentList
                        }
                    }
                }
        }
        downloadJobs[message.id] = job
    }

    fun cancelDownload(messageId: String) {
        downloadJobs[messageId]?.cancel()
        _downloadStates[messageId] = com.example.messenger_app.data.upload.EncryptedDownloadManager.DownloadState.Idle
    }

    fun downloadMedia(message: Message) {
        startDownload(message)
    }

    fun uploadMedia(uri: android.net.Uri, type: MessageType) {
        android.util.Log.d("ChatViewModel", "uploadMedia: Starting upload for $uri, type=$type")
        viewModelScope.launch {
            // 1. Create Temporary Message
            val tempId = java.util.UUID.randomUUID().toString()
            
            // Extract Metadata
            val metadata = mutableMapOf<String, String>()
            try {
                com.example.messenger_app.App.instance.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (nameIndex != -1) metadata["name"] = cursor.getString(nameIndex)
                        if (sizeIndex != -1) metadata["size"] = cursor.getLong(sizeIndex).toString()
                    }
                }
                val mimeType = com.example.messenger_app.App.instance.contentResolver.getType(uri)
                if (mimeType != null) {
                    metadata["mimeType"] = mimeType
                    
                    // Extract Dimensions
                    if (mimeType.startsWith("image")) {
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        com.example.messenger_app.App.instance.contentResolver.openInputStream(uri)?.use { 
                            android.graphics.BitmapFactory.decodeStream(it, null, options)
                        }
                        metadata["width"] = options.outWidth.toString()
                        metadata["height"] = options.outHeight.toString()
                    } else if (mimeType.startsWith("video")) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(com.example.messenger_app.App.instance, uri)
                            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                            
                            if (rotation == "90" || rotation == "270") {
                                metadata["width"] = height ?: "0"
                                metadata["height"] = width ?: "0"
                            } else {
                                metadata["width"] = width ?: "0"
                                metadata["height"] = height ?: "0"
                            }
                            
                            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            metadata["duration"] = duration ?: "0"
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "Error extracting video metadata", e)
                        } finally {
                            retriever.release()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Error extracting metadata", e)
            }

            val tempMessage = Message(
                id = tempId,
                senderId = currentUserId,
                senderName = currentUserName,
                encryptedContent = uri.toString(), // Local URI for preview/retry
                type = type,
                timestamp = System.currentTimeMillis(),
                metadata = metadata
            ).apply {
                isUploading = true
            }
            
            // Optimistic update
            val currentList = _messages.value.toMutableList()
            currentList.add(0, tempMessage)
            _messages.value = currentList

            try {
                // 2. Encrypt and Upload
                val publicUrl = encryptedUploadManager.encryptAndUpload(uri)
                
                // 3. Send Real Message
                val replyMsg = _replyToMessage.value
                
                // Determine Link Type
                val linkType = when(type) {
                    MessageType.IMAGE -> MessageType.IMAGE_LINK
                    MessageType.VIDEO -> MessageType.VIDEO_LINK
                    MessageType.FILE -> MessageType.FILE_LINK
                    else -> MessageType.FILE_LINK
                }

                val finalMessage = tempMessage.copy(
                    id = "", // Let Firestore generate ID
                    encryptedContent = publicUrl, // The Litterbox URL
                    type = linkType,
                    replyToId = replyMsg?.id,
                    replyPreview = replyMsg?.let { "${it.senderName}: ${getPreviewContent(it)}" }
                ).apply {
                    isUploading = false
                    isError = false
                }
                
                chatRepository.sendMessage(chatId, finalMessage, contactId)
                
                // Copy local file to EncryptedDownloadManager's cache so it appears as "downloaded" for the sender
                try {
                    val mimeType = metadata["mimeType"] ?: "application/octet-stream"
                    val name = metadata["name"]
                    // We need to know where EncryptedDownloadManager expects it
                    // We can use the helper method if we make it public or duplicate logic. 
                    // Better: use the manager to "import" it or just copy it manually.
                    // Since we have the publicUrl now, we can generate the expected filename.
                    // But wait, EncryptedDownloadManager uses url.hashCode() OR name.
                    // If name is present, it uses name.
                    val targetFile = encryptedDownloadManager.getLocalFile(publicUrl, mimeType, name) 
                        ?: java.io.File(encryptedDownloadManager.getOutputDir(mimeType), name ?: encryptedDownloadManager.generateLocalFileName(publicUrl, mimeType))
                    
                    if (!targetFile.exists()) {
                         com.example.messenger_app.App.instance.contentResolver.openInputStream(uri)?.use { input ->
                             java.io.FileOutputStream(targetFile).use { output ->
                                 input.copyTo(output)
                             }
                         }
                    }
                    
                    // Update the message in the list with localPath (although it will be replaced by Firestore update soon, 
                    // but for immediate UI feedback it's good)
                    // Actually, the flow collector will pick up the new message from Firestore. 
                    // But we should ensure the file is there BEFORE the flow updates.
                    android.util.Log.d("ChatViewModel", "Copied uploaded file to ${targetFile.absolutePath}")
                } catch (e: Exception) {
                    android.util.Log.e("ChatViewModel", "Failed to copy uploaded file to cache", e)
                }

                clearReply()
                
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "uploadMedia: Exception", e)
                _error.value = "Ошибка загрузки: ${e.message}"
                
                // Mark as Error instead of removing
                val list = _messages.value.toMutableList()
                val index = list.indexOfFirst { it.id == tempId }
                if (index != -1) {
                    list[index] = list[index].copy().apply {
                        isUploading = false
                        isError = true
                    }
                    _messages.value = list
                }
            }
        }
    }

    fun retryUpload(message: Message) {
        if (!message.isError) return
        
        // Remove the failed message from the list
        val list = _messages.value.toMutableList()
        list.remove(message)
        _messages.value = list

        // Restart upload with the original URI
        try {
            val uri = android.net.Uri.parse(message.encryptedContent)
            // Revert type to original (non-link) for upload process
            val originalType = when(message.type) {
                MessageType.IMAGE_LINK -> MessageType.IMAGE
                MessageType.VIDEO_LINK -> MessageType.VIDEO
                MessageType.FILE_LINK -> MessageType.FILE
                else -> message.type
            }
            uploadMedia(uri, originalType)
        } catch (e: Exception) {
            _error.value = "Не удалось повторить загрузку: ${e.message}"
        }
    }

    fun deleteMessage(messageId: String, deleteForEveryone: Boolean) {
        viewModelScope.launch {
            try {
                if (deleteForEveryone) {
                    chatRepository.deleteMessageForEveryone(chatId, messageId)
                } else {
                    chatRepository.deleteMessageForMe(chatId, messageId, currentUserId)
                }
            } catch (e: Exception) {
                _error.value = "Ошибка удаления: ${e.message}"
            }
        }
    }

    fun toggleReaction(messageId: String, emoji: String) {
        viewModelScope.launch {
            try {
                val message = _messages.value.find { it.id == messageId }
                if (message != null) {
                    val currentReaction = message.reactions[currentUserId]
                    val newEmoji = if (currentReaction == emoji) null else emoji
                    chatRepository.addReaction(chatId, messageId, currentUserId, newEmoji)
                }
            } catch (e: Exception) {
                _error.value = "Ошибка реакции: ${e.message}"
            }
        }
    }

    fun markAsRead(messageId: String) {
        viewModelScope.launch {
            try {
                chatRepository.markAsRead(chatId, messageId)
            } catch (e: Exception) {
                // Ignore error
            }
        }
    }



    // P2P Transfer
    // P2P Transfer removed

    fun downloadFile(message: Message) {
        if (message.type == MessageType.IMAGE_LINK || message.type == MessageType.VIDEO_LINK || message.type == MessageType.FILE_LINK) {
            downloadMedia(message)
        }
    }

    private fun getPreviewContent(message: Message): String {
        return when (message.type) {
            MessageType.TEXT -> message.encryptedContent
            MessageType.IMAGE, MessageType.IMAGE_LINK -> "Фото"
            MessageType.VIDEO, MessageType.VIDEO_LINK -> "Видео"
            MessageType.AUDIO -> "Голосовое сообщение"
            MessageType.FILE, MessageType.FILE_LINK -> "Файл"
            MessageType.SYSTEM -> message.encryptedContent
        }
    }

    private val _participants = MutableStateFlow<List<com.example.messenger_app.data.model.User>>(emptyList())
    val participants: StateFlow<List<com.example.messenger_app.data.model.User>> = _participants.asStateFlow()

    fun loadParticipants() {
        viewModelScope.launch {
            try {
                _participants.value = chatRepository.getChatParticipants(chatId)
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки участников: ${e.message}"
            }
        }
    }

    fun updateGroupName(newName: String) {
        viewModelScope.launch {
            try {
                chatRepository.updateGroupName(chatId, newName)
            } catch (e: Exception) {
                _error.value = "Ошибка обновления названия: ${e.message}"
            }
        }
    }

    fun addParticipant(userId: String) {
        viewModelScope.launch {
            try {
                chatRepository.addParticipant(chatId, userId, currentUserName)
                loadParticipants() // Refresh list
            } catch (e: Exception) {
                _error.value = "Ошибка добавления участника: ${e.message}"
            }
        }
    }

    fun removeParticipant(userId: String) {
        viewModelScope.launch {
            try {
                chatRepository.removeParticipant(chatId, userId)
                loadParticipants() // Refresh list
            } catch (e: Exception) {
                _error.value = "Ошибка удаления участника: ${e.message}"
            }
        }
    }
}
