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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.messenger_app.data.model.Message
import com.example.messenger_app.data.model.MessageType
import com.example.messenger_app.data.model.TransferStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun MessageItem(
    message: Message,
    isMyMessage: Boolean,
    onReply: (Message) -> Unit,
    onDelete: (String) -> Unit,
    onCopy: (String) -> Unit,
    onReaction: (String, String) -> Unit,
    onDownload: (Message) -> Unit = {},
    activeTransferId: String? = null,
    transferStatus: TransferStatus = TransferStatus.PENDING,
    transferProgress: Float = 0f
) {
    val alignment = if (isMyMessage) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (isMyMessage) Color(0xFF4A90E2) else Color(0xFFF0F2F5)
    val textColor = if (isMyMessage) Color.White else Color.Black
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

    if (showFullScreenImage && message.type == MessageType.IMAGE) {
        FullScreenImageDialog(imageUrl = message.encryptedContent) {
            showFullScreenImage = false
        }
    }

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
                        MessageType.IMAGE -> {
                            AsyncImage(
                                model = message.encryptedContent,
                                contentDescription = "Image",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        MessageType.VIDEO -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp)
                                    .background(Color.Black, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircleOutline,
                                    contentDescription = "Play Video",
                                    tint = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
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
                        MessageType.FILE -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InsertDriveFile,
                                    contentDescription = "File",
                                    tint = textColor,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text(text = "Файл", color = textColor, maxLines = 1)
                            }
                        }
                        MessageType.FILE_OFFER -> {
                            val parts = message.encryptedContent.split("|")
                            val transferId = parts.getOrNull(0) ?: ""
                            val fileName = parts.getOrNull(1) ?: "Unknown File"
                            val fileSize = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                            val sizeMb = String.format("%.2f MB", fileSize / (1024.0 * 1024.0))

                            Column(modifier = Modifier.widthIn(min = 200.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = "P2P File",
                                        tint = textColor,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    Column {
                                        Text(text = fileName, color = textColor, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                        Text(text = sizeMb, color = textColor.copy(alpha = 0.7f), fontSize = 12.sp)
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))

                                val isTransferring = activeTransferId == transferId && (transferStatus == TransferStatus.CONNECTING || transferStatus == TransferStatus.TRANSFERRING)
                                val isCompleted = activeTransferId == transferId && transferStatus == TransferStatus.COMPLETED

                                if (isTransferring) {
                                    LinearProgressIndicator(
                                        progress = { transferProgress },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(
                                        text = if (transferStatus == TransferStatus.CONNECTING) "Подключение..." else "${(transferProgress * 100).toInt()}%",
                                        color = textColor.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        modifier = Modifier.align(Alignment.End)
                                    )
                                } else {
                                    Button(
                                        onClick = { onDownload(message) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f)),
                                        modifier = Modifier.fillMaxWidth().height(36.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) {
                                        Text(text = "Скачать (P2P)", color = textColor, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Timestamp & Read Status
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
                                tint = textColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp)
                            )
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
                        Surface(
                            shape = CircleShape,
                            color = Color.White,
                            shadowElevation = 2.dp,
                            modifier = Modifier.padding(end = 4.dp)
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
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Ответить") },
                onClick = {
                    onReply(message)
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Копировать") },
                onClick = {
                    onCopy(message.encryptedContent)
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Реакции") },
                onClick = {
                    showReactions = true
                    showMenu = false
                }
            )
            if (isMyMessage) {
                DropdownMenuItem(
                    text = { Text("Удалить") },
                    onClick = {
                        onDelete(message.id)
                        showMenu = false
                    }
                )
            }
        }

        // Reactions Dialog
        if (showReactions) {
            ReactionDialog(
                onDismiss = { showReactions = false },
                onEmojiSelected = { emoji ->
                    onReaction(message.id, emoji)
                    showReactions = false
                }
            )
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
