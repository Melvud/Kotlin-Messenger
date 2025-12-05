package com.example.messenger_app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.messenger_app.data.model.Message
import com.example.messenger_app.data.model.MessageType

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.InsertDriveFile

import com.example.messenger_app.data.upload.EncryptedDownloadManager

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun MessageItem(
    message: Message,
    isMyMessage: Boolean,
    onReply: (Message) -> Unit,
    onDelete: (String) -> Unit,
    onCopy: (String) -> Unit,
    onReaction: (String, String) -> Unit,
    onRetry: (Message) -> Unit = {},
    onDownload: (Message) -> Unit = {},
    onCancel: (String) -> Unit = {},
    downloadState: EncryptedDownloadManager.DownloadState? = null
) {
    val alignment = if (isMyMessage) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (isMyMessage) Color(0xFFE1FFC7) else Color.White
    val textColor = Color.Black
    val shape = if (isMyMessage) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp)
    }

    var showFullScreenImage by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showReactions by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Swipe to Reply State
    var offsetX by remember { mutableStateOf(0f) }
    val swipeThreshold = 100f



    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .offset(x = offsetX.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX > swipeThreshold) {
                            onReply(message)
                        }
                        offsetX = 0f
                    }
                ) { change, dragAmount ->
                    // change.consume() 
                    if (dragAmount > 0) { // Only swipe right
                        offsetX = (offsetX + dragAmount).coerceIn(0f, swipeThreshold + 20f)
                    }
                }
            },
        contentAlignment = alignment
    ) {
        Column(
            horizontalAlignment = if (isMyMessage) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .combinedClickable(
                        onClick = {
                            if (message.type == MessageType.IMAGE) showFullScreenImage = true
                        },
                        onLongClick = { showMenu = true }
                    )
                    .background(color = backgroundColor, shape = shape)
                    .padding(8.dp)
                    .widthIn(max = 280.dp)
            ) {
                Column {
                    // Reply Preview
                    message.replyPreview?.let { preview ->
                        Row(
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                .padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(IntrinsicSize.Min)
                                    .background(textColor)
                            )
                            Text(
                                text = preview,
                                color = textColor.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }

                    // Message Content
                    when (message.type) {
                        MessageType.TEXT -> {
                            Text(
                                text = message.encryptedContent,
                                color = textColor,
                                fontSize = 16.sp
                            )
                        }
                        MessageType.IMAGE, MessageType.IMAGE_LINK -> {
                            val isLink = message.type == MessageType.IMAGE_LINK
                            val localFile = if (isLink) {
                                message.localPath?.let { java.io.File(it) }
                            } else null
                            
                            val isDownloading = downloadState is EncryptedDownloadManager.DownloadState.Downloading
                            val progress = (downloadState as? EncryptedDownloadManager.DownloadState.Downloading)?.progress ?: 0f
                            val showDownload = isLink && localFile == null && !isDownloading
                            val model = if (isLink) localFile else message.encryptedContent

                            // Aspect Ratio Logic
                            val width = message.metadata["width"]?.toFloatOrNull() ?: 1f
                            val height = message.metadata["height"]?.toFloatOrNull() ?: 1f
                            val aspectRatio = (width / height).coerceIn(0.5f, 2f) // Limit aspect ratio

                            Box(contentAlignment = Alignment.Center) {
                                AsyncImage(
                                    model = model,
                                    contentDescription = "Image",
                                    modifier = Modifier
                                        .fillMaxWidth() // Fill width of the bubble
                                        .aspectRatio(aspectRatio) // Dynamic aspect ratio
                                        .clip(RoundedCornerShape(8.dp))
                                        .let {
                                            if (message.isUploading || showDownload || isDownloading) it.alpha(0.5f) else it
                                        }
                                        .clickable {
                                            if (localFile != null || !isLink) {
                                                showFullScreenImage = true
                                            }
                                        },
                                    contentScale = ContentScale.Crop
                                )
                                
                                // Timestamp Overlay
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = formatTimestamp(message.timestamp),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                        if (isMyMessage && message.isRead) {
                                            Icon(
                                                imageVector = Icons.Default.DoneAll,
                                                contentDescription = "Read",
                                                tint = Color(0xFF3B82F6),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        } else if (isMyMessage) {
                                            Icon(
                                                imageVector = Icons.Default.Done,
                                                contentDescription = "Sent",
                                                tint = Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }

                                if (message.isUploading) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                                if (isDownloading) {
                                    Box(contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(
                                            progress = { progress },
                                            color = Color.White,
                                            modifier = Modifier.size(48.dp),
                                        )
                                        IconButton(onClick = { onCancel(message.id) }) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Cancel",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                                if (showDownload) {
                                    IconButton(onClick = { onDownload(message) }) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Download",
                                            tint = Color.White,
                                            modifier = Modifier.size(48.dp)
                                        )
                                    }
                                }
                                if (message.isError || downloadState is EncryptedDownloadManager.DownloadState.Error) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(Color.Black.copy(alpha = 0.5f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                imageVector = Icons.Default.Error,
                                                contentDescription = "Error",
                                                tint = Color.Red,
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Text(
                                                text = "Ошибка",
                                                color = Color.White,
                                                fontSize = 12.sp
                                            )
                                            IconButton(onClick = { onRetry(message) }) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = "Retry",
                                                    tint = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (showFullScreenImage) {
                                val uri = if (isLink) localFile?.absolutePath ?: "" else message.encryptedContent
                                if (uri.isNotEmpty()) {
                                    FullScreenMediaViewer(uri = uri, isVideo = false) {
                                        showFullScreenImage = false
                                    }
                                }
                            }
                        }
                        MessageType.VIDEO, MessageType.VIDEO_LINK -> {
                            val isLink = message.type == MessageType.VIDEO_LINK
                            val localFile = if (isLink) {
                                message.localPath?.let { java.io.File(it) }
                            } else null
                            
                            val isDownloading = downloadState is EncryptedDownloadManager.DownloadState.Downloading
                            val progress = (downloadState as? EncryptedDownloadManager.DownloadState.Downloading)?.progress ?: 0f
                            val showDownload = isLink && localFile == null && !isDownloading

                            // Aspect Ratio Logic
                            val width = message.metadata["width"]?.toFloatOrNull() ?: 1f
                            val height = message.metadata["height"]?.toFloatOrNull() ?: 1f
                            val aspectRatio = (width / height).coerceIn(0.5f, 2f)

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(aspectRatio) // Dynamic aspect ratio
                                    .background(Color.Black, RoundedCornerShape(8.dp))
                                    .clip(RoundedCornerShape(8.dp)) // Clip content
                                    .clickable {
                                         if (localFile != null || !isLink) {
                                             showFullScreenImage = true
                                         }
                                    }
                                    .let {
                                        if (message.isUploading || showDownload || isDownloading) it.alpha(0.5f) else it
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                // Video Preview (if downloaded)
                                if (localFile != null) {
                                    VideoPlayerPreview(
                                        uri = localFile.absolutePath,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                
                                // Timestamp Overlay
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = formatTimestamp(message.timestamp),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(end = 4.dp)
                                        )
                                        if (isMyMessage && message.isRead) {
                                            Icon(
                                                imageVector = Icons.Default.DoneAll,
                                                contentDescription = "Read",
                                                tint = Color(0xFF3B82F6),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        } else if (isMyMessage) {
                                            Icon(
                                                imageVector = Icons.Default.Done,
                                                contentDescription = "Sent",
                                                tint = Color.White.copy(alpha = 0.8f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }

                                if (message.isUploading) {
                                    CircularProgressIndicator(color = Color.White)
                                } else if (isDownloading) {
                                    Box(contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(
                                            progress = { progress },
                                            color = Color.White,
                                            modifier = Modifier.size(48.dp),
                                        )
                                        IconButton(onClick = { onCancel(message.id) }) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Cancel",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                } else if (message.isError || downloadState is EncryptedDownloadManager.DownloadState.Error) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.Error,
                                            contentDescription = "Error",
                                            tint = Color.Red,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        IconButton(onClick = { onRetry(message) }) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Retry",
                                                tint = Color.White
                                            )
                                        }
                                    }
                                } else if (showDownload) {
                                    IconButton(onClick = { onDownload(message) }) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Download",
                                            tint = Color.White,
                                            modifier = Modifier.size(48.dp)
                                        )
                                    }
                                } 
                                // Removed Play button overlay since it auto-plays or opens on click
                            }
                            
                            if (showFullScreenImage) {
                                val uri = if (isLink) localFile?.absolutePath ?: "" else message.encryptedContent
                                if (uri.isNotEmpty()) {
                                    FullScreenMediaViewer(uri = uri, isVideo = true) {
                                        showFullScreenImage = false
                                    }
                                }
                            }
                        }
                        MessageType.AUDIO -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    val player = com.example.messenger_app.utils.AudioPlayer(context)
                                    player.playFile(android.net.Uri.parse(message.encryptedContent)) {}
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play Audio",
                                        tint = textColor
                                    )
                                }
                                Text(text = "Голосовое сообщение", color = textColor)
                            }
                        }
                        MessageType.FILE, MessageType.FILE_LINK -> {
                            val isLink = message.type == MessageType.FILE_LINK
                            val localFile = if (isLink) {
                                message.localPath?.let { java.io.File(it) }
                            } else null
                            val showDownload = isLink && localFile == null

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Gray.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = "File",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = message.metadata["name"] ?: "File",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (showDownload) {
                                        Text(
                                            text = "Tap to download",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.Gray
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                if (showDownload) {
                                    IconButton(onClick = { onDownload(message) }) {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = "Download",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }



                    // Timestamp & Read Status (Only for non-media, as media has overlay)
                    if (message.type != MessageType.IMAGE && message.type != MessageType.IMAGE_LINK && 
                        message.type != MessageType.VIDEO && message.type != MessageType.VIDEO_LINK) {
                        Row(
                            modifier = Modifier.align(Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = formatTimestamp(message.timestamp),
                                color = textColor.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(end = 4.dp)
                            )

                            if (isMyMessage && message.isRead) {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Read",
                                    tint = Color(0xFF3B82F6), // Blue check
                                    modifier = Modifier.size(16.dp)
                                )
                            } else if (isMyMessage) {
                                 Icon(
                                    imageVector = Icons.Default.Done,
                                    contentDescription = "Sent",
                                    tint = textColor.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Reactions Chips
            if (message.reactions.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = if (isMyMessage) Arrangement.End else Arrangement.Start
                ) {
                    message.reactions.values.distinct().forEach { emoji ->
                        val count = message.reactions.values.count { it == emoji }
                        val isMe = message.reactions[com.example.messenger_app.AppGraph.userRepo.currentUser()?.uid] == emoji
                        Surface(
                            shape = CircleShape,
                            color = if (isMe) Color(0xFFE3F2FD) else Color.White,
                            border = if (isMe) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2196F3)) else null,
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clickable { onReaction(message.id, emoji) }
                        ) {
                            Text(
                                text = "$emoji $count",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }

        // Menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = (-8).dp)
        ) {
            // Inline Reactions
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
                emojis.forEach { emoji ->
                    val isSelected = message.reactions[com.example.messenger_app.AppGraph.userRepo.currentUser()?.uid] == emoji
                    Text(
                        text = emoji,
                        fontSize = 24.sp,
                        modifier = Modifier
                            .clickable {
                                onReaction(message.id, emoji)
                                showMenu = false
                            }
                            .background(
                                if (isSelected) Color(0xFFE3F2FD) else Color.Transparent,
                                CircleShape
                            )
                            .padding(4.dp)
                    )
                }
            }
            
            HorizontalDivider()

            DropdownMenuItem(
                text = { Text("Ответить") },
                onClick = {
                    onReply(message)
                    showMenu = false
                },
                leadingIcon = { Icon(Icons.Default.Reply, contentDescription = null) }
            )
            DropdownMenuItem(
                text = { Text("Копировать") },
                onClick = {
                    onCopy(message.encryptedContent)
                    showMenu = false
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) }
            )
            if (isMyMessage) {
                DropdownMenuItem(
                    text = { Text("Удалить", color = Color.Red) },
                    onClick = {
                        onDelete(message.id)
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red) }
                )
            }
        }
    }
}

@Composable
fun ReactionDialog(
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit
) {
    val emojis = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White
        ) {
            Row(modifier = Modifier.padding(16.dp)) {
                emojis.forEach { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 24.sp,
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable { onEmojiSelected(emoji) }
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}


