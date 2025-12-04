package com.example.messenger_app.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
                    fileTransferManager = AppGraph.fileTransferManager,
                    callsRepository = AppGraph.callsRepo
                )
            }
        }
    )

    val messages by viewModel.messages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val replyToMessage by viewModel.replyToMessage.collectAsState()
    val transferStatus by viewModel.transferStatus.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val activeTransferId by viewModel.activeTransferId.collectAsState()

    // Media Picker
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            viewModel.uploadMedia(uri, com.example.messenger_app.data.model.MessageType.IMAGE)
        }
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
    val isOtherUserTyping by viewModel.isOtherUserTyping.collectAsState()

    val navigateToCall by viewModel.navigateToCall.collectAsState()
    
    LaunchedEffect(navigateToCall) {
        navigateToCall?.let { callInfo ->
            onNavigateToCall(callInfo)
            viewModel.onCallNavigated()
        }
    }
    
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
                            Text(
                                text = if (isOnline) "в сети" else "не в сети", 
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
                // Message List
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(messages) { message ->
                        MessageItem(
                            message = message,
                            isMyMessage = message.senderId == currentUserId,
                            onReply = { viewModel.setReplyToMessage(it) },
                            onDelete = { viewModel.deleteMessage(it) },
                            onCopy = { 
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Message", it)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                            },
                            onReaction = { msgId, emoji -> viewModel.addReaction(msgId, emoji) },
                            onDownload = { viewModel.downloadFile(it) },
                            activeTransferId = activeTransferId,
                            transferStatus = transferStatus,
                            transferProgress = transferProgress
                        )
                    }
                }

                // Input Area
                ChatInputArea(
                    replyToMessage = replyToMessage,
                    onCancelReply = { viewModel.clearReply() },
                    onSend = { text ->
                        viewModel.sendMessage(text)
                    },
                    onAttach = {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
                                viewModel.uploadMedia(android.net.Uri.fromFile(file), com.example.messenger_app.data.model.MessageType.AUDIO)
                            }
                        }
                    },
                    onTyping = { viewModel.onTyping(it) }
                )
            }

            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

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
                        color = Color(0xFF4A90E2),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        text = if (replyToMessage.type == com.example.messenger_app.data.model.MessageType.TEXT) 
                            replyToMessage.encryptedContent 
                        else 
                            "Медиа",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                IconButton(onClick = onCancelReply) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel Reply", tint = Color.Gray)
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
                Icon(Icons.Default.AttachFile, contentDescription = "Attach", tint = Color.Gray)
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
                        tint = if (isRecording) Color.Red else Color.Gray
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
                        tint = Color(0xFF4A90E2)
                    )
                }
            }
        }
    }
}
