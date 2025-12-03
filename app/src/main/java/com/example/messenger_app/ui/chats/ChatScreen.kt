@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.messenger_app.ui.chats

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.getstream.chat.android.compose.ui.messages.list.MessageList
import io.getstream.chat.android.compose.ui.messages.composer.MessageComposer
import io.getstream.chat.android.compose.ui.theme.ChatTheme
import io.getstream.chat.android.compose.ui.theme.StreamTypography
import io.getstream.chat.android.compose.viewmodel.messages.MessagesViewModelFactory
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.messenger_app.data.CallsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * ЭКРАН ЧАТА (STREAM CHAT VERSION)
 * С кастомным хедером для сохранения функционала звонков
 * Используем компоненты нижнего уровня (MessageList, MessageComposer) вместо MessagesScreen
 */
@Composable
fun ChatScreen(
    chatId: String?, // Stream Channel CID (e.g. messaging:123)
    otherUserId: String,
    otherUserName: String,
    isGroup: Boolean = false, // New parameter
    onBack: () -> Unit,
    onAudioCall: (String) -> Unit,
    onVideoCall: (String) -> Unit,
    onDeleteChat: (String) -> Unit // New callback
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val callsRepo = remember { CallsRepository(auth, db) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // State for call ID generation
    var callId by remember { mutableStateOf<String?>(null) }
    
    // State for delete dialog
    var showDeleteDialog by remember { mutableStateOf(false) }

    // State for selected message options
    var selectedMessage by remember { mutableStateOf<io.getstream.chat.android.models.Message?>(null) }

    // Если chatId не передан, мы не можем показать чат Stream сразу.
    if (chatId == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить чат?") },
            text = { Text("Вы уверены, что хотите удалить этот чат? Все сообщения будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteChat(chatId)
                    }
                ) {
                    Text("Удалить", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // State for thread
    var activeThread by remember { mutableStateOf<io.getstream.chat.android.models.Message?>(null) }

    ChatTheme(
        typography = StreamTypography.defaultTypography().let {
            it.copy(
                body = it.body.copy(fontSize = 18.sp),
                bodyBold = it.bodyBold.copy(fontSize = 18.sp),
                title3 = it.title3.copy(fontSize = 20.sp),
                captionBold = it.captionBold.copy(fontSize = 14.sp)
            )
        }
    ) {
        if (activeThread != null) {
            // THREAD VIEW
            val threadViewModelFactory = MessagesViewModelFactory(
                context = androidx.compose.ui.platform.LocalContext.current,
                channelId = chatId,
                parentMessageId = activeThread!!.id
            )
            val threadListViewModel = viewModel(
                modelClass = io.getstream.chat.android.compose.viewmodel.messages.MessageListViewModel::class.java,
                factory = threadViewModelFactory,
                key = "thread_${activeThread!!.id}"
            )
            val threadComposerViewModel = viewModel(
                modelClass = io.getstream.chat.android.compose.viewmodel.messages.MessageComposerViewModel::class.java,
                factory = threadViewModelFactory,
                key = "thread_composer_${activeThread!!.id}"
            )

            // Handle file picking for thread
            val pickFileThread = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    val attachment = io.getstream.chat.android.models.Attachment(
                        type = "file",
                        extraData = mutableMapOf("uri" to uri.toString())
                    )
                    threadComposerViewModel.addSelectedAttachments(listOf(attachment))
                }
            }
            
            // Handle media picking for thread
            val pickMediaThread = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                if (uris.isNotEmpty()) {
                    val attachments = uris.map { uri ->
                        val type = context.contentResolver.getType(uri)
                        val attachmentType = when {
                            type?.startsWith("video/") == true -> "video"
                            else -> "image"
                        }
                        // Get file name and size
                        var name = "file"
                        var size = 0L
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (nameIndex != -1) name = cursor.getString(nameIndex)
                                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                            }
                        }

                        io.getstream.chat.android.models.Attachment(
                            type = attachmentType,
                            upload = java.io.File(uri.path ?: ""), // This might not be enough for content URIs, but SDK handles it via uploadId usually
                            uploadState = io.getstream.chat.android.models.Attachment.UploadState.Idle,
                            extraData = mutableMapOf("uri" to uri.toString()),
                            mimeType = type,
                            fileSize = size.toInt(),
                            title = name
                        )
                    }
                    threadComposerViewModel.addSelectedAttachments(attachments)
                }
            }
            
            // Thread Attachment Dialog
            var showThreadAttachmentDialog by remember { mutableStateOf(false) }
            if (showThreadAttachmentDialog) {
                 AlertDialog(
                    onDismissRequest = { showThreadAttachmentDialog = false },
                    title = { Text("Выберите тип вложения") },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showThreadAttachmentDialog = false }) {
                            Text("Отмена")
                        }
                    },
                    text = {
                        Column {
                            TextButton(onClick = {
                                showThreadAttachmentDialog = false
                                pickMediaThread.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }) {
                                Text("Фото и Видео")
                            }
                            TextButton(onClick = {
                                showThreadAttachmentDialog = false
                                pickFileThread.launch("*/*")
                            }) {
                                Text("Файл")
                            }
                        }
                    }
                )
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Обсуждение", color = Color.White) },
                        navigationIcon = {
                            IconButton(onClick = { activeThread = null }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            titleContentColor = Color.White,
                            navigationIconContentColor = Color.White
                        )
                    )
                },
                bottomBar = {
                    MessageComposer(
                        viewModel = threadComposerViewModel,
                        onSendMessage = { message -> threadComposerViewModel.sendMessage(message) },
                        onAttachmentsClick = { showThreadAttachmentDialog = true }
                    )
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    MessageList(
                        viewModel = threadListViewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

        } else {
            // MAIN CHAT VIEW
            val viewModelFactory = MessagesViewModelFactory(
                context = androidx.compose.ui.platform.LocalContext.current,
                channelId = chatId,
                messageLimit = 30
            )
            val listViewModel = viewModel(
                modelClass = io.getstream.chat.android.compose.viewmodel.messages.MessageListViewModel::class.java,
                factory = viewModelFactory
            )
            val composerViewModel = viewModel(
                modelClass = io.getstream.chat.android.compose.viewmodel.messages.MessageComposerViewModel::class.java,
                factory = viewModelFactory
            )
            
            // Picker for attachments (Main Chat)
            val pickMediaMain = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                if (uris.isNotEmpty()) {
                    val attachments = uris.map { uri ->
                        val type = context.contentResolver.getType(uri)
                        val attachmentType = when {
                            type?.startsWith("video/") == true -> "video"
                            else -> "image"
                        }
                        // Get file name and size
                        var name = "file"
                        var size = 0L
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                                if (nameIndex != -1) name = cursor.getString(nameIndex)
                                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                            }
                        }

                        io.getstream.chat.android.models.Attachment(
                            type = attachmentType,
                            upload = java.io.File(uri.path ?: ""),
                            uploadState = io.getstream.chat.android.models.Attachment.UploadState.Idle,
                            extraData = mutableMapOf("uri" to uri.toString()),
                            mimeType = type,
                            fileSize = size.toInt(),
                            title = name
                        )
                    }
                    composerViewModel.addSelectedAttachments(attachments)
                }
            }

            val pickFileMain = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) {
                    val attachment = io.getstream.chat.android.models.Attachment(
                        type = "file",
                        extraData = mutableMapOf("uri" to uri.toString())
                    )
                    composerViewModel.addSelectedAttachments(listOf(attachment))
                }
            }
            
            // Main Attachment Dialog
            var showMainAttachmentDialog by remember { mutableStateOf(false) }
            if (showMainAttachmentDialog) {
                 AlertDialog(
                    onDismissRequest = { showMainAttachmentDialog = false },
                    title = { Text("Выберите тип вложения") },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { showMainAttachmentDialog = false }) {
                            Text("Отмена")
                        }
                    },
                    text = {
                        Column {
                            TextButton(onClick = {
                                showMainAttachmentDialog = false
                                pickMediaMain.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            }) {
                                Text("Фото и Видео")
                            }
                            TextButton(onClick = {
                                showMainAttachmentDialog = false
                                pickFileMain.launch("*/*")
                            }) {
                                Text("Файл")
                            }
                        }
                    }
                )
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = otherUserName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                            }
                        },
                        actions = {
                            if (!isGroup) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val id = callId ?: callsRepo.startCall(otherUserId, "video").id
                                                callId = id
                                                onVideoCall(id)
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
                                                val id = callId ?: callsRepo.startCall(otherUserId, "audio").id
                                                callId = id
                                                onAudioCall(id)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.Call, "Звонок", tint = Color.White)
                                }
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Default.Delete, "Удалить чат", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            titleContentColor = Color.White,
                            navigationIconContentColor = Color.White,
                            actionIconContentColor = Color.White
                        )
                    )
                },
                bottomBar = {
                    MessageComposer(
                        viewModel = composerViewModel,
                        onSendMessage = { message ->
                            composerViewModel.sendMessage(message)
                        },
                        onAttachmentsClick = {
                            showMainAttachmentDialog = true
                        }
                    )
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    MessageList(
                        viewModel = listViewModel,
                        modifier = Modifier.fillMaxSize(),
                        onThreadClick = { message: io.getstream.chat.android.models.Message ->
                            activeThread = message
                        },
                        onLongItemClick = { message: io.getstream.chat.android.models.Message ->
                            selectedMessage = message
                        }
                    )
                }
            }

            // Message Options Bottom Sheet
            if (selectedMessage != null) {
                ModalBottomSheet(
                    onDismissRequest = { selectedMessage = null }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Действия с сообщением",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        // Reply Option
                        DropdownMenuItem(
                            text = { Text("Ответить") },
                            onClick = {
                                selectedMessage?.let { msg ->
                                    // TODO: Fix MessageAction.Reply unresolved reference
                                    // composerViewModel.performMessageAction(
                                    //     io.getstream.chat.android.ui.common.state.messages.MessageAction.Reply(msg)
                                    // )
                                }
                                selectedMessage = null
                            },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                            }
                        )

                        // Reactions
                        Text(
                            text = "Реакции",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            val reactions = listOf(
                                "like" to "👍",
                                "love" to "❤️",
                                "haha" to "😂",
                                "wow" to "😲",
                                "sad" to "😢",
                                "angry" to "😡"
                            )
                            reactions.forEach { (type, emoji) ->
                                IconButton(onClick = {
                                    selectedMessage?.let { msg ->
                                        // TODO: Fix MessageAction.React unresolved reference
                                        // composerViewModel.performMessageAction(
                                        //     io.getstream.chat.android.ui.common.state.messages.MessageAction.React(msg, io.getstream.chat.android.models.Reaction(messageId = msg.id, type = type, score = 1))
                                        // )
                                    }
                                    selectedMessage = null
                                }) {
                                    Text(emoji, fontSize = 24.sp)
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // Delete Option
                        DropdownMenuItem(
                            text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                selectedMessage?.let { msg ->
                                    listViewModel.deleteMessage(msg)
                                }
                                selectedMessage = null
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                            }
                        )
                        
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}