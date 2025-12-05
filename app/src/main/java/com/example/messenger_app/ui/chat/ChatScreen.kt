package com.example.messenger_app.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.messenger_app.AppGraph
import com.example.messenger_app.ui.chat.components.MessageItem
import com.example.messenger_app.utils.TimeUtils
import kotlinx.coroutines.launch

private val AccentColor = Color(0xFF3B82F6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: String,
    contactId: String,
    contactName: String,
    contactToken: String,
    onBackClick: () -> Unit,
    onNavigateToCall: (com.example.messenger_app.data.CallInfo) -> Unit
) {
    val context = LocalContext.current
    val currentUser = AppGraph.userRepo.currentUser()
    val currentUserId = currentUser?.uid ?: ""
    val currentUserName = currentUser?.displayName ?: "Unknown"

    // Manual ViewModel Factory
    val viewModel: ChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    chatRepository = AppGraph.chatRepo,
                    chatId = chatId,
                    currentUserId = currentUserId,
                    currentUserName = currentUserName,
                    contactId = contactId,
                    targetUserToken = contactToken,

                    callsRepository = AppGraph.callsRepo,
                    encryptedUploadManager = AppGraph.encryptedUploadManager,
                    encryptedDownloadManager = AppGraph.encryptedDownloadManager
                )
            }
        }
    )

    // Track Active Chat
    DisposableEffect(chatId) {
        com.example.messenger_app.AppState.setActiveChatId(chatId)
        onDispose {
            com.example.messenger_app.AppState.setActiveChatId(null)
        }
    }

    val messages by viewModel.messages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val replyToMessage by viewModel.replyToMessage.collectAsState()


    // Media Picker
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
        if (uri != null) {
            // Determine type based on MIME type (simplified)
            val type = if (context.contentResolver.getType(uri)?.startsWith("video") == true) {
                com.example.messenger_app.data.model.MessageType.VIDEO
            } else {
                com.example.messenger_app.data.model.MessageType.IMAGE
            }
            viewModel.uploadMedia(uri, type)
        }
        }
    }



    var showAttachmentSheet by remember { mutableStateOf(false) }

    if (showAttachmentSheet) {
        AttachmentBottomSheet(
            onDismiss = { showAttachmentSheet = false },
            onPickMedia = {
                showAttachmentSheet = false
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },

        )
    }

    // Audio Recorder
    val audioRecorder = remember { com.example.messenger_app.utils.AudioRecorder(context) }
    var audioFile: java.io.File? by remember { mutableStateOf(null) }

    // Permissions
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions granted/denied
    }

    // Handle Error Toast
    LaunchedEffect(error) {
        error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    val isOnline by viewModel.isOnline.collectAsState()
    val lastSeen by viewModel.lastSeen.collectAsState()
    val isOtherUserTyping by viewModel.isOtherUserTyping.collectAsState()

    val navigateToCall by viewModel.navigateToCall.collectAsState()
    
    LaunchedEffect(navigateToCall) {
        navigateToCall?.let { callInfo ->
            onNavigateToCall(callInfo)
            viewModel.onCallNavigated()
        }
    }
    
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    // ... (existing code)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = contactName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (isOtherUserTyping) {
                            Text(
                                text = "печатает...",
                                fontSize = 12.sp,
                                color = AccentColor,
                                modifier = Modifier.animateContentSize()
                            )
                        } else {
                            val statusText = if (isOnline) {
                                "в сети"
                            } else {
                                lastSeen?.let { TimeUtils.formatLastSeen(it) } ?: "не в сети"
                            }
                             Text(
                                text = statusText,
                                fontSize = 12.sp, 
                                color = if (isOnline) Color(0xFF4CAF50) else Color.Gray
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.initiateCall(isVideo = false) }) {
                        Icon(Icons.Default.Call, contentDescription = "Voice Call", tint = AccentColor)
                    }
                    IconButton(onClick = { viewModel.initiateCall(isVideo = true) }) {
                        Icon(Icons.Default.Videocam, contentDescription = "Video Call", tint = AccentColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Group Messages
                val uiItems = remember(messages) {
                    val items = mutableListOf<ChatUiItem>()
                    messages.forEachIndexed { index, message ->
                        items.add(ChatUiItem.MessageItem(message))
                        
                        val nextMessage = messages.getOrNull(index + 1)
                        val isLast = nextMessage == null
                        val isDateChange = nextMessage != null && !isSameDay(message.timestamp, nextMessage.timestamp)
                        
                        if (isLast || isDateChange) {
                            items.add(ChatUiItem.DateHeader(message.timestamp))
                        }
                    }
                    items
                }

                // Message List
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(8.dp)
                ) {

                    items(uiItems) { item ->
                        when (item) {
                            is ChatUiItem.MessageItem -> {
                                val message = item.message
                                MessageItem(
                                    message = message,
                                    isMyMessage = message.senderId == currentUserId,
                                    onReply = { viewModel.setReplyToMessage(it) },
                                    onDelete = { showDeleteDialog = it },
                                    onCopy = { 
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        val clip = android.content.ClipData.newPlainText("Message", it)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                                    },
                                    onReaction = { msgId, emoji -> viewModel.toggleReaction(msgId, emoji) },
                                    onRetry = { viewModel.retryUpload(it) },
                                    onDownload = { viewModel.downloadFile(it) },
                                    onCancel = { viewModel.cancelDownload(it) },
                                    downloadState = viewModel.downloadStates[message.id]
                                )
                                // ...
                            }
                            is ChatUiItem.DateHeader -> {
                                DateHeader(timestamp = item.timestamp)
                            }
                        }
                    }
                }
                // ... (Input Area)


                // Input Area
                ChatInputArea(
                    replyToMessage = replyToMessage,
                    onCancelReply = { viewModel.clearReply() },
                    onSend = { text ->
                        viewModel.sendMessage(text)
                    },
                    onAttach = {
                        showAttachmentSheet = true
                    },
                    onRecord = { start ->
                        if (start) {
                            // Check permissions first
                            val permissions = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
                            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                            // Simplified permission check for demo
                            val file = java.io.File(context.cacheDir, "audio_${System.currentTimeMillis()}.m4a")
                            audioFile = file
                            audioRecorder.startRecording(file)
                        } else {
                            audioRecorder.stopRecording()
                            audioFile?.let { file ->
                                // P2P Audio removed, need to implement uploadMedia for audio if needed
                                // For now, just log or toast
                                Toast.makeText(context, "Audio upload not implemented yet", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onTyping = { viewModel.onTyping(it) }
                )
            }
            
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        if (showDeleteDialog != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = null },
                title = { Text("Удалить сообщение?") },
                text = { Text("Вы хотите удалить это сообщение?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteMessage(showDeleteDialog!!, deleteForEveryone = false)
                            showDeleteDialog = null
                        }
                    ) {
                        Text("Удалить у меня")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteMessage(showDeleteDialog!!, deleteForEveryone = true)
                            showDeleteDialog = null
                        }
                    ) {
                        Text("Удалить у всех", color = Color.Red)
                    }
                }
            )
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputArea(
    replyToMessage: com.example.messenger_app.data.model.Message?,
    onCancelReply: () -> Unit,
    onSend: (String) -> Unit,
    onAttach: () -> Unit,
    onRecord: (Boolean) -> Unit, // true = start, false = stop
    onTyping: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }

    Column {
        // Reply Panel
        if (replyToMessage != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(32.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = "Ответ: ${replyToMessage.senderName}",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (replyToMessage.type == com.example.messenger_app.data.model.MessageType.TEXT) 
                            replyToMessage.encryptedContent 
                        else 
                            "Медиа",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onCancelReply) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel Reply", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onAttach) {
                Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            TextField(
                value = text,
                onValueChange = { 
                    text = it
                    onTyping(it)
                },
                placeholder = { Text(if (isRecording) "Запись..." else "Сообщение...") },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                maxLines = 3,
                enabled = !isRecording
            )

            if (text.isBlank()) {
                // Mic Icon
                IconButton(
                    onClick = { /* Click to record not implemented, use hold */ },
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isRecording = true
                                onRecord(true)
                                tryAwaitRelease()
                                isRecording = false
                                onRecord(false)
                            }
                        )
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Record",
                        tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Send Icon
                IconButton(
                    onClick = {
                        onSend(text)
                        text = ""
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentBottomSheet(
    onDismiss: () -> Unit,
    onPickMedia: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Прикрепить",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            ListItem(
                headlineContent = { Text("Фото или Видео") },
                leadingContent = { 
                    Icon(Icons.Default.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.primary) 
                },
                modifier = Modifier.clickable { onPickMedia() }
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

sealed interface ChatUiItem {
    data class MessageItem(val message: com.example.messenger_app.data.model.Message) : ChatUiItem
    data class DateHeader(val timestamp: Long) : ChatUiItem
}

@Composable
fun DateHeader(timestamp: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = formatDateHeader(timestamp),
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color(0xFF4CAF50).copy(alpha = 0.8f), RoundedCornerShape(12.dp)) // Greenish bubble
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}

fun isSameDay(ts1: Long, ts2: Long): Boolean {
    val cal1 = java.util.Calendar.getInstance().apply { timeInMillis = ts1 }
    val cal2 = java.util.Calendar.getInstance().apply { timeInMillis = ts2 }
    return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
            cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
}

fun formatDateHeader(timestamp: Long): String {
    val now = java.util.Calendar.getInstance()
    val date = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    
    return when {
        now.get(java.util.Calendar.YEAR) == date.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == date.get(java.util.Calendar.DAY_OF_YEAR) -> "Сегодня"
        
        now.get(java.util.Calendar.YEAR) == date.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) - 1 == date.get(java.util.Calendar.DAY_OF_YEAR) -> "Вчера"
        
        else -> {
            val sdf = java.text.SimpleDateFormat("d MMMM", java.util.Locale("ru"))
            sdf.format(date.time)
        }
    }
}
