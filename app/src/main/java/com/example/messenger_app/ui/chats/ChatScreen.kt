@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.messenger_app.ui.chats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
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
 * ЭКРАН ЧАТА С СОВРЕМЕННЫМ ДИЗАЙНОМ
 * ИСПРАВЛЕНО: Правильная обработка null chatId
 */
@Composable
fun ChatScreen(
    chatId: String?,
    otherUserId: String,
    otherUserName: String,
    onBack: () -> Unit,
    onAudioCall: (String) -> Unit,
    onVideoCall: (String) -> Unit
) {
    val context = LocalContext.current
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val storage = remember { FirebaseStorage.getInstance() }
    val chatRepo = remember { ChatRepository(auth, db, storage) }
    val callsRepo = remember { CallsRepository(auth, db) }

    var actualChatId by remember { mutableStateOf(chatId) }

    // ИСПРАВЛЕНО: Используем actualChatId ?: "" чтобы избежать пустого потока
    val messages by chatRepo.getMessagesFlow(actualChatId ?: "").collectAsState(initial = emptyList())

    val chat by produceState<Chat?>(initialValue = null, actualChatId) {
        actualChatId?.let { id ->
            if (id.isNotBlank()) {
                value = chatRepo.getChat(id)
            }
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

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(messages, actualChatId) {
        if (actualChatId != null && actualChatId!!.isNotBlank()) {
            val unreadMessages = messages
                .filter { it.senderId != myUid && it.status != MessageStatus.READ }
                .map { it.id }
            if (unreadMessages.isNotEmpty()) {
                chatRepo.markMessagesAsRead(actualChatId!!, unreadMessages)
            }
        }
    }

    var typingJob: kotlinx.coroutines.Job? by remember { mutableStateOf(null) }
    LaunchedEffect(messageText, actualChatId) {
        typingJob?.cancel()
        if (actualChatId != null && actualChatId!!.isNotBlank() && messageText.isNotBlank()) {
            chatRepo.setTyping(actualChatId!!, true)
            typingJob = scope.launch {
                delay(3000)
                if (actualChatId != null && actualChatId!!.isNotBlank()) {
                    chatRepo.setTyping(actualChatId!!, false)
                }
            }
        } else if (actualChatId != null && actualChatId!!.isNotBlank()) {
            chatRepo.setTyping(actualChatId!!, false)
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
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
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
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
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
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
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
                        if (otherUserPhoto != null) {
                            Image(
                                painter = rememberAsyncImagePainter(otherUserPhoto),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .shadow(2.dp, CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .shadow(2.dp, CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = otherUserName.firstOrNull()?.uppercase() ?: "?",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Column {
                            Text(
                                text = otherUserName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Color.White
                            )
                            AnimatedVisibility(visible = isOtherUserTyping) {
                                Text(
                                    text = "печатает...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                ),
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val callInfo = callsRepo.startCall(otherUserId, "video")
                                    onVideoCall(callInfo.id)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Videocam, "Видеозвонок", tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    val callInfo = callsRepo.startCall(otherUserId, "audio")
                                    onAudioCall(callInfo.id)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Default.Call, "Звонок", tint = Color.White)
                    }
                }
            )
        },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = replyToMessage != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    replyToMessage?.let { reply ->
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .height(40.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                RoundedCornerShape(2.dp)
                                            )
                                    )
                                    Column {
                                        Text(
                                            text = reply.senderName,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = reply.content,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                                IconButton(onClick = { replyToMessage = null }) {
                                    Icon(Icons.Default.Close, "Отменить")
                                }
                            }
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledIconButton(
                            onClick = { showAttachMenu = true },
                            modifier = Modifier.size(46.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Add, "Вложения")
                        }

                        OutlinedTextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    "Сообщение",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            maxLines = 5,
                            enabled = !isSending
                        )

                        if (messageText.isNotBlank()) {
                            FilledIconButton(
                                onClick = {
                                    scope.launch {
                                        isSending = true
                                        try {
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
                                            if (actualChatId!!.isNotBlank()) {
                                                chatRepo.setTyping(actualChatId!!, false)
                                            }
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        } finally {
                                            isSending = false
                                        }
                                    }
                                },
                                modifier = Modifier.size(46.dp),
                                enabled = !isSending,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                if (isSending) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Send,
                                        "Отправить",
                                        tint = Color.White
                                    )
                                }
                            }
                        } else {
                            FilledIconButton(
                                onClick = { },
                                modifier = Modifier.size(46.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
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
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "empty")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 1.15f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(1200, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "scale"
                        )

                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Chat,
                                contentDescription = null,
                                modifier = Modifier.size(50.dp * scale),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                        }
                        Text(
                            "Нет сообщений",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            "Начните общение с ${otherUserName}!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        ModernMessageBubble(
                            message = message,
                            isMyMessage = message.senderId == myUid,
                            onLongClick = { replyToMessage = message }
                        )
                    }
                }
            }
        }

        if (showAttachMenu) {
            ModernAttachmentMenu(
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
private fun ModernMessageBubble(
    message: Message,
    isMyMessage: Boolean,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = if (isMyMessage) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isMyMessage) 20.dp else 6.dp,
                bottomEnd = if (isMyMessage) 6.dp else 20.dp
            ),
            color = if (isMyMessage) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick
                )
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                if (message.replyToId != null && message.replyToText != null) {
                    Surface(
                        color = if (isMyMessage)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(36.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(1.5.dp)
                                    )
                            )
                            Column {
                                Text(
                                    text = message.senderName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = message.replyToText,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                when (message.type) {
                    MessageType.TEXT -> {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    MessageType.IMAGE -> {
                        Image(
                            painter = rememberAsyncImagePainter(message.content),
                            contentDescription = null,
                            modifier = Modifier
                                .widthIn(max = 240.dp)
                                .heightIn(max = 320.dp)
                                .clip(RoundedCornerShape(14.dp)),
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
                                        .widthIn(max = 240.dp)
                                        .heightIn(max = 320.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Icon(
                                Icons.Default.PlayCircle,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(56.dp)
                                    .align(Alignment.Center),
                                tint = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                    MessageType.FILE -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = message.fileName ?: "Файл",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
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
                    }
                    MessageType.STICKER -> {
                        Image(
                            painter = rememberAsyncImagePainter(message.content),
                            contentDescription = null,
                            modifier = Modifier.size(160.dp)
                        )
                    }
                    else -> {
                        Text(
                            text = "Неподдерживаемый тип",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (message.isEdited) {
                        Text(
                            text = "изм.",
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
private fun ModernAttachmentMenu(
    onDismiss: () -> Unit,
    onImageClick: () -> Unit,
    onVideoClick: () -> Unit,
    onFileClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отправить файл", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModernAttachmentOption(
                    icon = Icons.Default.Image,
                    text = "Фото или изображение",
                    color = MaterialTheme.colorScheme.primary,
                    onClick = onImageClick
                )
                ModernAttachmentOption(
                    icon = Icons.Default.Videocam,
                    text = "Видео",
                    color = MaterialTheme.colorScheme.tertiary,
                    onClick = onVideoClick
                )
                ModernAttachmentOption(
                    icon = Icons.Default.InsertDriveFile,
                    text = "Файл или документ",
                    color = MaterialTheme.colorScheme.secondary,
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
private fun ModernAttachmentOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.1f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
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