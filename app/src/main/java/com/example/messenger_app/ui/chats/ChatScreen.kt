@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.messenger_app.ui.chats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.messenger_app.data.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * ЭКРАН ЧАТА С КНОПКАМИ ЗВОНКОВ
 * Чат создается только при отправке первого сообщения (как в Telegram)
 */
@Composable
fun ChatScreen(
    chatId: String?,           // null если чат еще не создан
    otherUserId: String,       // UID собеседника
    otherUserName: String,     // Имя собеседника
    onBack: () -> Unit,
    onAudioCall: (String) -> Unit,  // callId
    onVideoCall: (String) -> Unit   // callId
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val storage = remember { FirebaseStorage.getInstance() }
    val chatRepo = remember { ChatRepository(auth, db, storage) }
    val callsRepo = remember { CallsRepository(auth, db) }

    // Реальный ID чата (может измениться при первом сообщении)
    var actualChatId by remember { mutableStateOf(chatId) }

    val messages by chatRepo.getMessagesFlow(actualChatId ?: "").collectAsState(initial = emptyList())
    val chat by produceState<Chat?>(initialValue = null, actualChatId) {
        actualChatId?.let { id ->
            value = chatRepo.getChat(id)
        }
    }

    val myUid = auth.currentUser?.uid ?: ""
    val otherUserPhoto = chat?.participantPhotos?.get(otherUserId)
    val isOtherUserTyping = chat?.typingUsers?.contains(otherUserId) == true

    var messageText by remember { mutableStateOf("") }
    var replyToMessage by remember { mutableStateOf<Message?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Автоматическая прокрутка к последнему сообщению
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Пометить сообщения как прочитанные
    LaunchedEffect(messages, actualChatId) {
        if (actualChatId != null) {
            val unreadMessages = messages
                .filter { it.senderId != myUid && it.status != MessageStatus.READ }
                .map { it.id }
            if (unreadMessages.isNotEmpty()) {
                chatRepo.markMessagesAsRead(actualChatId!!, unreadMessages)
            }
        }
    }

    // Индикатор печати
    var typingJob: kotlinx.coroutines.Job? by remember { mutableStateOf(null) }
    LaunchedEffect(messageText, actualChatId) {
        typingJob?.cancel()
        if (actualChatId != null && messageText.isNotBlank()) {
            chatRepo.setTyping(actualChatId!!, true)
            typingJob = scope.launch {
                delay(3000)
                chatRepo.setTyping(actualChatId!!, false)
            }
        } else if (actualChatId != null) {
            chatRepo.setTyping(actualChatId!!, false)
        }
    }

    // Лаунчеры для медиафайлов
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                if (actualChatId == null) {
                    actualChatId = chatRepo.getOrCreateChat(otherUserId)
                }
                val fileName = "image_${System.currentTimeMillis()}.jpg"
                val fileSize = context.contentResolver.openInputStream(uri)?.available()?.toLong() ?: 0L
                val upload = MediaUpload(
                    localUri = uri.toString(),
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = "image/jpeg",
                    type = MessageType.IMAGE
                )
                chatRepo.sendMediaMessage(context, actualChatId!!, upload)
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                if (actualChatId == null) {
                    actualChatId = chatRepo.getOrCreateChat(otherUserId)
                }
                val fileName = "video_${System.currentTimeMillis()}.mp4"
                val fileSize = context.contentResolver.openInputStream(uri)?.available()?.toLong() ?: 0L
                val upload = MediaUpload(
                    localUri = uri.toString(),
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = "video/mp4",
                    type = MessageType.VIDEO
                )
                chatRepo.sendMediaMessage(context, actualChatId!!, upload)
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                if (actualChatId == null) {
                    actualChatId = chatRepo.getOrCreateChat(otherUserId)
                }
                val fileName = "file_${System.currentTimeMillis()}"
                val fileSize = context.contentResolver.openInputStream(uri)?.available()?.toLong() ?: 0L
                val upload = MediaUpload(
                    localUri = uri.toString(),
                    fileName = fileName,
                    fileSize = fileSize,
                    mimeType = "application/octet-stream",
                    type = MessageType.FILE
                )
                chatRepo.sendMediaMessage(context, actualChatId!!, upload)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Аватар собеседника
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (otherUserPhoto != null) {
                                Image(
                                    painter = rememberAsyncImagePainter(otherUserPhoto),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = otherUserName.firstOrNull()?.uppercase() ?: "?",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        Column {
                            Text(
                                text = otherUserName,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isOtherUserTyping) {
                                Text(
                                    text = "печатает...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    // Кнопка видеозвонка
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val callInfo = callsRepo.startCall(otherUserId, "video")
                                    onVideoCall(callInfo.id)
                                } catch (e: Exception) {
                                    // Обработка ошибки
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Videocam, "Видеозвонок")
                    }
                    // Кнопка аудиозвонка
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val callInfo = callsRepo.startCall(otherUserId, "audio")
                                    onAudioCall(callInfo.id)
                                } catch (e: Exception) {
                                    // Обработка ошибки
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Call, "Звонок")
                    }
                }
            )
        },
        bottomBar = {
            Column {
                // Панель ответа на сообщение
                AnimatedVisibility(
                    visible = replyToMessage != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    replyToMessage?.let { reply ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = reply.senderName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = reply.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { replyToMessage = null }) {
                                    Icon(Icons.Default.Close, "Отменить")
                                }
                            }
                        }
                    }
                }

                // Панель ввода сообщения
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        // Кнопка вложений
                        IconButton(
                            onClick = { showAttachMenu = true }
                        ) {
                            Icon(Icons.Default.AttachFile, "Вложения")
                        }

                        // Поле ввода
                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            placeholder = { Text("Сообщение") },
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            maxLines = 5,
                            enabled = !isSending
                        )

                        // Кнопка отправки
                        if (messageText.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        isSending = true
                                        try {
                                            // Создаем чат при первом сообщении
                                            if (actualChatId == null) {
                                                actualChatId = chatRepo.getOrCreateChat(otherUserId)
                                            }

                                            chatRepo.sendTextMessage(
                                                chatId = actualChatId!!,
                                                text = messageText,
                                                replyToId = replyToMessage?.id,
                                                replyToText = replyToMessage?.content
                                            )
                                            messageText = ""
                                            replyToMessage = null
                                            chatRepo.setTyping(actualChatId!!, false)
                                        } finally {
                                            isSending = false
                                        }
                                    }
                                },
                                enabled = !isSending
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        "Отправить",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            IconButton(onClick = { /* Голосовое сообщение */ }) {
                                Icon(Icons.Default.Mic, "Голосовое")
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (messages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        )
                        Text(
                            "Нет сообщений",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            "Начните общение с ${otherUserName}!",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            isMyMessage = message.senderId == myUid,
                            onLongClick = { replyToMessage = message }
                        )
                    }
                }
            }
        }

        // Меню вложений
        if (showAttachMenu) {
            AttachmentMenu(
                onDismiss = { showAttachMenu = false },
                onImageClick = {
                    showAttachMenu = false
                    imagePickerLauncher.launch("image/*")
                },
                onVideoClick = {
                    showAttachMenu = false
                    videoPickerLauncher.launch("video/*")
                },
                onFileClick = {
                    showAttachMenu = false
                    filePickerLauncher.launch("*/*")
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    isMyMessage: Boolean,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (isMyMessage) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isMyMessage) 18.dp else 4.dp,
                bottomEnd = if (isMyMessage) 4.dp else 18.dp
            ),
            color = if (isMyMessage) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick
                )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                // Если есть ответ на сообщение
                if (message.replyToId != null && message.replyToText != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Text(
                                text = message.senderName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = message.replyToText,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Содержимое сообщения
                when (message.type) {
                    MessageType.TEXT -> {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    MessageType.IMAGE -> {
                        Image(
                            painter = rememberAsyncImagePainter(message.content),
                            contentDescription = null,
                            modifier = Modifier
                                .widthIn(max = 250.dp)
                                .heightIn(max = 300.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    MessageType.VIDEO -> {
                        Box {
                            if (message.thumbnailUrl != null) {
                                Image(
                                    painter = rememberAsyncImagePainter(message.thumbnailUrl),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .widthIn(max = 250.dp)
                                        .heightIn(max = 300.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Icon(
                                Icons.Default.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(48.dp)
                                    .align(Alignment.Center),
                                tint = Color.White
                            )
                        }
                    }
                    MessageType.FILE -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.InsertDriveFile, null)
                            Column {
                                Text(
                                    text = message.fileName ?: "Файл",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                message.fileSize?.let { size ->
                                    Text(
                                        text = formatFileSize(size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                    MessageType.STICKER -> {
                        Image(
                            painter = rememberAsyncImagePainter(message.content),
                            contentDescription = null,
                            modifier = Modifier.size(150.dp)
                        )
                    }
                    else -> {
                        Text(
                            text = "Неподдерживаемый тип сообщения",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                // Время и статус
                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isEdited) {
                        Text(
                            text = "изменено",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    message.timestamp?.let { timestamp ->
                        Text(
                            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(timestamp.toDate()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    if (isMyMessage) {
                        Icon(
                            imageVector = when (message.status) {
                                MessageStatus.SENDING -> Icons.Default.Schedule
                                MessageStatus.SENT -> Icons.Default.Done
                                MessageStatus.DELIVERED -> Icons.Default.DoneAll
                                MessageStatus.READ -> Icons.Default.DoneAll
                                MessageStatus.FAILED -> Icons.Default.Error
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = when (message.status) {
                                MessageStatus.READ -> MaterialTheme.colorScheme.primary
                                MessageStatus.FAILED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentMenu(
    onDismiss: () -> Unit,
    onImageClick: () -> Unit,
    onVideoClick: () -> Unit,
    onFileClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отправить") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AttachmentOption(
                    icon = Icons.Default.Image,
                    text = "Фото",
                    onClick = onImageClick
                )
                AttachmentOption(
                    icon = Icons.Default.Videocam,
                    text = "Видео",
                    onClick = onVideoClick
                )
                AttachmentOption(
                    icon = Icons.Default.InsertDriveFile,
                    text = "Файл",
                    onClick = onFileClick
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun AttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> "${bytes / (1024 * 1024)} МБ"
    }
}