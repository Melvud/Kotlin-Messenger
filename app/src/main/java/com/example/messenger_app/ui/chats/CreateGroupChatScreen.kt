@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.messenger_app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.messenger_app.AppGraph
import com.example.messenger_app.data.Contact
import com.example.messenger_app.data.ContactsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@Composable
fun CreateGroupChatScreen(
    onBack: () -> Unit,
    onGroupCreated: (String) -> Unit // Returns CID
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val contactsRepo = remember { ContactsRepository(auth, db) }
    val chatRepo = AppGraph.chatRepo
    val scope = rememberCoroutineScope()

    var groupName by remember { mutableStateOf("") }
    var selectedUsers by remember { mutableStateOf<Set<Contact>>(emptySet()) }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Загружаем контакты (в реальном приложении лучше пагинация или поиск)
    LaunchedEffect(Unit) {
        // Для простоты загружаем всех или используем поиск. 
        // Здесь сделаем имитацию загрузки "всех" или популярных.
        // В реальном проекте лучше использовать поиск.
        // Пока просто оставим пустым и будем использовать поиск, как в ChatsListScreen?
        // Или лучше сразу показать список, если есть возможность.
        // ContactsRepository имеет searchUsersByUsernameFlow.
        // Давайте сделаем просто поиск для добавления участников.
    }

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Contact>>(emptyList()) }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        contactsRepo.searchUsersByUsernameFlow(searchQuery).collect { users ->
            searchResults = users.map { user ->
                Contact(id = user.uid, username = user.username, name = user.name)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Новая группа", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                actions = {
                    if (groupName.isNotBlank() && selectedUsers.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                if (isLoading) return@IconButton
                                isLoading = true
                                scope.launch {
                                    val cid = chatRepo.createGroupChat(
                                        name = groupName,
                                        memberIds = selectedUsers.map { it.id }
                                    )
                                    isLoading = false
                                    if (cid != null) {
                                        onGroupCreated(cid)
                                    }
                                }
                            }
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                            } else {
                                Icon(Icons.Default.Check, "Создать", tint = Color.White)
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Название группы
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = { Text("Название группы") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Участники: ${selectedUsers.size}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // Выбранные участники (горизонтальный список)
            if (selectedUsers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(selectedUsers.toList()) { user ->
                        InputChip(
                            selected = true,
                            onClick = { selectedUsers = selectedUsers - user },
                            label = { Text(user.name) },
                            trailingIcon = { Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Поиск участников
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск участников...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            // Результаты поиска
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(searchResults) { contact ->
                    val isSelected = selectedUsers.contains(contact)
                    UserSelectionItem(
                        contact = contact,
                        isSelected = isSelected,
                        onClick = {
                            selectedUsers = if (isSelected) {
                                selectedUsers - contact
                            } else {
                                selectedUsers + contact
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UserSelectionItem(
    contact: Contact,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
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
                text = contact.name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = contact.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )

        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() }
        )
    }
}
