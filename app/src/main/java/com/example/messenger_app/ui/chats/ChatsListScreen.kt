@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.messenger_app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.getstream.chat.android.compose.ui.channels.ChannelsScreen
import io.getstream.chat.android.compose.ui.theme.ChatTheme
import io.getstream.chat.android.compose.ui.theme.StreamTypography
import com.example.messenger_app.AppGraph
import com.google.firebase.auth.FirebaseAuth
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.launch
import io.getstream.chat.android.models.Channel

/**
 * ГЛАВНЫЙ ЭКРАН МЕССЕНДЖЕРА ANTIMAX (STREAM CHAT VERSION)
 */
@Composable
fun ChatsListScreen(
    onChatClick: (String?, String, String, Boolean) -> Unit,
    onNewChatClick: () -> Unit,
    onProfileClick: () -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val chatClient = AppGraph.chatClient
    val chatRepo = AppGraph.chatRepo

    // State to track connection status
    var isConnected by remember { mutableStateOf(chatClient.clientState.user.value != null) }

    // Подключаем пользователя, если еще не подключен
    LaunchedEffect(Unit) {
        if (!isConnected) {
            val success = chatRepo.connectUser()
            isConnected = success
        }
    }

    // State for deletion dialog
    var channelToDelete by remember { mutableStateOf<io.getstream.chat.android.models.Channel?>(null) }
    val scope = rememberCoroutineScope()

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
        if (!isConnected) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                "Antimax",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                color = Color.White
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        actions = {
                            // Profile Icon
                            Box(
                                modifier = Modifier
                                    .padding(end = 16.dp)
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .clickable(onClick = onProfileClick),
                                contentAlignment = Alignment.Center
                            ) {
                                val photoUrl = auth.currentUser?.photoUrl
                                if (photoUrl != null) {
                                    AsyncImage(
                                        model = photoUrl,
                                        contentDescription = "Profile",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Text(
                                        text = auth.currentUser?.displayName?.firstOrNull()?.uppercase() ?: "?",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = onNewChatClick,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Default.Edit, "Новый чат")
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    ChannelsScreen(
                        title = "Чаты",
                        isShowingHeader = false,
                        onItemClick = { channel ->
                            val otherMember = channel.members.firstOrNull { it.user.id != auth.currentUser?.uid }
                            val isGroup = channel.memberCount > 2 || channel.extraData.containsKey("name")
                            
                            val name = if (isGroup) {
                                channel.name.takeIf { it.isNotBlank() } ?: "Group Chat"
                            } else {
                                otherMember?.user?.name ?: "Chat"
                            }
                            
                            val targetId = if (isGroup) "group" else (otherMember?.user?.id ?: "")
                            
                            onChatClick(channel.cid, targetId, name, isGroup)
                        },
                        onBackPressed = { },
                        onHeaderActionClick = { }
                    )
                }
            }

            if (channelToDelete != null) {
                AlertDialog(
                    onDismissRequest = { channelToDelete = null },
                    title = { Text("Удалить чат?") },
                    text = { Text("Вы уверены, что хотите удалить этот чат? Все сообщения будут удалены безвозвратно.") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val cid = channelToDelete?.cid
                                if (cid != null) {
                                    scope.launch {
                                        chatRepo.deleteChannel(cid)
                                        channelToDelete = null
                                    }
                                }
                            }
                        ) {
                            Text("Удалить", color = Color.Red)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { channelToDelete = null }) {
                            Text("Отмена")
                        }
                    }
                )
            }
        }
    }
}