package com.example.messenger_app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.messenger_app.AppGraph
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListScreen(
    onChatClick: (String, String, String, Boolean) -> Unit,
    onNewChatClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    val currentUser = FirebaseAuth.getInstance().currentUser
    var chats by remember { mutableStateOf<List<ChatSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            AppGraph.chatRepo.getChatsFlow(currentUser.uid).collect { chatList ->
                val summaries = chatList.map { chat ->
                    val participants = chat.participants
                    val isGroup = chat.isGroup
                    var name = chat.name.ifBlank { "Chat" }
                    val lastMessage = chat.lastMessage
                    
                    val otherUserId = if (isGroup) "" else participants.firstOrNull { it != currentUser.uid } ?: ""

                    if (!isGroup && otherUserId.isNotEmpty()) {
                        if (name == "Chat" || name.isBlank()) {
                            try {
                                val userDoc = AppGraph.chatRepo.firestore.collection("users").document(otherUserId).get().await()
                                name = userDoc.getString("name") ?: userDoc.getString("username") ?: "User"
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }
                    }
                    ChatSummary(chat.id, name, lastMessage, otherUserId, isGroup, chat.timestamp)
                }
                chats = summaries
                isLoading = false
            }
        }
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedChat by remember { mutableStateOf<ChatSummary?>(null) }
    val scope = rememberCoroutineScope()

    if (showDeleteDialog && selectedChat != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить чат?") },
            text = { Text("Вы хотите удалить этот чат?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                AppGraph.chatRepo.deleteChatForEveryone(selectedChat!!.id)
                                chats = chats.filter { it.id != selectedChat!!.id }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            showDeleteDialog = false
                        }
                    }
                ) {
                    Text("Удалить у всех")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                if (currentUser != null) {
                                    AppGraph.chatRepo.deleteChatForMe(selectedChat!!.id, currentUser.uid)
                                    chats = chats.filter { it.id != selectedChat!!.id }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            showDeleteDialog = false
                        }
                    }
                ) {
                    Text("Удалить у меня")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Чаты", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onProfileClick) {
                        Icon(Icons.Default.Person, "Profile")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChatClick,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, "New Chat")
            }
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(chats) { chat ->
                    ChatListItem(
                        chat = chat,
                        onClick = { onChatClick(chat.id, chat.otherUserId, chat.name, chat.isGroup) },
                        onLongClick = {
                            selectedChat = chat
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ChatListItem(
    chat: ChatSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(onClick = onClick) // Note: Long click needs combinedClickable or pointerInput
            .height(80.dp), // Fixed height for consistency
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(generateColor(chat.name)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chat.name.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Content
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = chat.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = formatTime(chat.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = chat.lastMessage.ifBlank { "Нет сообщений" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

fun generateColor(name: String): Color {
    val hash = name.hashCode()
    val r = (hash and 0xFF0000 shr 16)
    val g = (hash and 0x00FF00 shr 8)
    val b = (hash and 0x0000FF)
    return Color(r, g, b).copy(alpha = 1f).let { 
        // Ensure color is not too light or too dark
        Color(
            red = (it.red + 0.5f) / 2,
            green = (it.green + 0.5f) / 2,
            blue = (it.blue + 0.5f) / 2
        )
    }
}

fun formatTime(timestamp: Long): String {
    if (timestamp == 0L) return ""
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

data class ChatSummary(
    val id: String, 
    val name: String, 
    val lastMessage: String, 
    val otherUserId: String,
    val isGroup: Boolean,
    val timestamp: Long
)
