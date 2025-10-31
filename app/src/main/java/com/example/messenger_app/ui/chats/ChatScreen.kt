@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.example.messenger_app.ui.chats

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.rememberAsyncImagePainter
import com.example.messenger_app.data.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

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
    var messageToEdit by remember { mutableStateOf<Message?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var isSending by remember { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    var showMessageOptions by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Устанавливаем активный чат при входе
    LaunchedEffect(actualChatId) {
        actualChatId?.let { chatRepo.setActiveChat(it) }
    }

    // Очищаем активный чат при выходе
    DisposableEffect(Unit) {
        onDispose {
            scope.launch {
                chatRepo.setActiveChat(null)
                actualChatId?.let { chatRepo.setTyping(it, false) }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(messages, actualChatId) {
        // Если у нас ещё нет actualChatId, попробуем создать/получить его (новый чат).
        if ((actualChatId == null || actualChatId!!.isBlank()) && messages.isNotEmpty()) {
            try {
                actualChatId = chatRepo.getOrCreateChat(otherUserId)
            } catch (e: Exception) {
                // логируем, но продолжаем — не хотим крушить UI
                e.printStackTrace()
            }
        }

        if (actualChatId != null && actualChatId!!.isNotBlank()) {
            val unreadMessages = messages
                .filter { it.senderId != myUid && it.status != MessageStatus.READ }
                .map { it.id }
            if (unreadMessages.isNotEmpty()) {
                try {
                    chatRepo.markMessagesAsRead(actualChatId!!, unreadMessages)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }


    // Обработка печати
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
                            // Анимированный индикатор печати
                            AnimatedVisibility(
                                visible = isOtherUserTyping,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "печатает",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.9f),
                                        fontWeight = FontWeight.Medium
                                    )
                                    TypingAnimation()
                                }
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
                // Реплай
                AnimatedVisibility(
                    visible = replyToMessage != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    replyToMessage?.let { reply ->
                        ReplyPreview(
                            message = reply,
                            onCancel = { replyToMessage = null }
                        )
                    }
                }

                // Редактирование
                AnimatedVisibility(
                    visible = messageToEdit != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    messageToEdit?.let { edit ->
                        EditPreview(
                            message = edit,
                            onCancel = {
                                messageToEdit = null
                                messageText = ""
                            }
                        )
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
                                    if (messageToEdit != null) "Редактирование..." else "Сообщение",
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

                                            if (messageToEdit != null) {
                                                chatRepo.editMessage(
                                                    chatId = actualChatId!!,
                                                    messageId = messageToEdit!!.id,
                                                    newText = messageText
                                                )
                                                messageToEdit = null
                                            } else {
                                                chatRepo.sendTextMessage(
                                                    chatId = actualChatId!!,
                                                    text = messageText,
                                                    replyToId = replyToMessage?.id,
                                                    replyToText = replyToMessage?.content
                                                )
                                                replyToMessage = null
                                            }

                                            messageText = ""
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
                                        if (messageToEdit != null) Icons.Default.Check else Icons.AutoMirrored.Filled.Send,
                                        if (messageToEdit != null) "Сохранить" else "Отправить",
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
                EmptyChatView(otherUserName = otherUserName)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        val deletedFor = message.deletedFor
                        val isDeletedForMe = deletedFor.contains(myUid)

                        if (!isDeletedForMe) {
                            SwipeableMessageBubble(
                                message = message,
                                isMyMessage = message.senderId == myUid,
                                onLongClick = {
                                    selectedMessage = message
                                    showMessageOptions = true
                                },
                                onSwipeReply = {
                                    replyToMessage = message
                                }
                            )
                        }
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

        if (showMessageOptions && selectedMessage != null) {
            MessageOptionsDialog(
                message = selectedMessage!!,
                isMyMessage = selectedMessage!!.senderId == myUid,
                onDismiss = { showMessageOptions = false },
                onReply = {
                    replyToMessage = selectedMessage
                    showMessageOptions = false
                },
                onEdit = {
                    if (selectedMessage!!.type == MessageType.TEXT) {
                        messageToEdit = selectedMessage
                        messageText = selectedMessage!!.content
                    }
                    showMessageOptions = false
                },
                onDeleteForMe = {
                    scope.launch {
                        actualChatId?.let {
                            chatRepo.deleteMessage(it, selectedMessage!!.id, forEveryone = false)
                        }
                    }
                    showMessageOptions = false
                },
                onDeleteForEveryone = {
                    scope.launch {
                        actualChatId?.let {
                            chatRepo.deleteMessage(it, selectedMessage!!.id, forEveryone = true)
                        }
                    }
                    showMessageOptions = false
                }
            )
        }
    }
}

// ==================== АНИМАЦИЯ ПЕЧАТАНИЯ ====================

@Composable
private fun TypingAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 200)
                ),
                label = "dot$index"
            )

            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = alpha))
            )
        }
    }
}

// ==================== СВАЙПАЕМОЕ СООБЩЕНИЕ ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableMessageBubble(
    message: Message,
    isMyMessage: Boolean,
    onLongClick: () -> Unit,
    onSwipeReply: () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    val maxSwipe = 100f
    val density = LocalDensity.current

    val swipeDirection = if (isMyMessage) -1f else 1f // Влево для своих, вправо для чужих

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX.absoluteValue > maxSwipe / 2) {
                            onSwipeReply()
                        }
                        offsetX = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        val newOffset = offsetX + dragAmount
                        // Ограничиваем свайп правильным направлением
                        offsetX = if (swipeDirection > 0) {
                            newOffset.coerceIn(0f, maxSwipe)
                        } else {
                            newOffset.coerceIn(-maxSwipe, 0f)
                        }
                    }
                )
            },
        horizontalArrangement = if (isMyMessage) Arrangement.End else Arrangement.Start
    ) {
        Box {
            // Иконка реплая, появляющаяся при свайпе
            if (offsetX.absoluteValue > 10f) {
                Icon(
                    Icons.Default.Reply,
                    contentDescription = "Ответить",
                    modifier = Modifier
                        .align(if (isMyMessage) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = 16.dp)
                        .graphicsLayer {
                            alpha = (offsetX.absoluteValue / maxSwipe).coerceIn(0f, 1f)
                            scaleX = (offsetX.absoluteValue / maxSwipe).coerceIn(0.5f, 1f)
                            scaleY = (offsetX.absoluteValue / maxSwipe).coerceIn(0.5f, 1f)
                        },
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
            }

            // Само сообщение
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
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
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
                        // Показываем "изм." если сообщение отредактировано
                        if (message.isEdited) {
                            Text(
                                text = "изм.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 10.sp
                            )
                        }
                        message.timestamp?.let { timestamp ->
                            Text(
                                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(timestamp.toDate()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontSize = 11.sp
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
}

// ==================== UI КОМПОНЕНТЫ ====================

@Composable
private fun EmptyChatView(otherUserName: String) {
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
                "Начните общение с $otherUserName!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun ReplyPreview(
    message: Message,
    onCancel: () -> Unit
) {
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Reply,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = message.senderName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, "Отменить")
            }
        }
    }
}

@Composable
private fun EditPreview(
    message: Message,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
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
                            MaterialTheme.colorScheme.tertiary,
                            RoundedCornerShape(2.dp)
                        )
                )
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Text(
                            text = "Редактирование сообщения",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, "Отменить")
            }
        }
    }
}

@Composable
private fun MessageOptionsDialog(
    message: Message,
    isMyMessage: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Опции сообщения",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                MessageOptionItem(
                    icon = Icons.Default.Reply,
                    text = "Ответить",
                    onClick = {
                        onReply()
                        onDismiss()
                    }
                )

                if (isMyMessage && message.type == MessageType.TEXT) {
                    MessageOptionItem(
                        icon = Icons.Default.Edit,
                        text = "Редактировать",
                        onClick = {
                            onEdit()
                            onDismiss()
                        }
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                MessageOptionItem(
                    icon = Icons.Default.DeleteOutline,
                    text = "Удалить у меня",
                    color = MaterialTheme.colorScheme.error,
                    onClick = {
                        onDeleteForMe()
                        onDismiss()
                    }
                )

                if (isMyMessage) {
                    MessageOptionItem(
                        icon = Icons.Default.DeleteForever,
                        text = "Удалить у всех",
                        color = MaterialTheme.colorScheme.error,
                        onClick = {
                            onDeleteForEveryone()
                            onDismiss()
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Отмена")
                }
            }
        }
    }
}

@Composable
private fun MessageOptionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = color
            )
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