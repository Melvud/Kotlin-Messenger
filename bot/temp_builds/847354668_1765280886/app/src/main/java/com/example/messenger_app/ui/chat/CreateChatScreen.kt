package com.example.messenger_app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.messenger_app.AppGraph
import com.example.messenger_app.data.UserProfile
import kotlinx.coroutines.launch

// --- Modern Dark Theme Colors ---
private val DarkBackground = Color(0xFF101010)
private val DarkSurface = Color(0xFF1E1E1E)
private val AccentColor = Color(0xFF3B82F6) // Modern Blue
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0B0B0)
private val DividerColor = Color(0xFF2A2A2A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChatScreen(
    onBack: () -> Unit,
    onChatCreated: (String, String, String, Boolean) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var isGroupMode by remember { mutableStateOf(false) }
    var selectedUsers by remember { mutableStateOf<Set<UserProfile>>(emptySet()) }
    var showGroupNameDialog by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val currentUid = AppGraph.sessionManager.getUid()
    val focusManager = LocalFocusManager.current

    // Search Logic
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            AppGraph.contactsRepo.searchUsersByUsernameFlow(searchQuery).collect { users ->
                searchResults = users
            }
        } else {
            searchResults = emptyList()
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isGroupMode) "Новая группа" else "Новый чат",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        if (isGroupMode) {
                            Text(
                                text = "${selectedUsers.size} выбрано",
                                style = MaterialTheme.typography.bodySmall,
                                color = AccentColor
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isGroupMode) {
                            isGroupMode = false
                            selectedUsers = emptySet()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    if (isGroupMode && selectedUsers.isNotEmpty()) {
                        IconButton(onClick = { showGroupNameDialog = true }) {
                            Icon(Icons.Default.Check, "Create Group", tint = AccentColor)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground,
                    scrolledContainerColor = DarkBackground
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DarkBackground)
        ) {
            // --- Modern Search Bar ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(DarkSurface),
                    placeholder = { 
                        Text(
                            "Поиск по @username...", 
                            color = TextSecondary.copy(alpha = 0.7f)
                        ) 
                    },
                    leadingIcon = { 
                        Icon(
                            Icons.Default.Search, 
                            "Search", 
                            tint = if (searchQuery.isNotEmpty()) AccentColor else TextSecondary 
                        ) 
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, "Clear", tint = TextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = DarkSurface,
                        unfocusedContainerColor = DarkSurface,
                        disabledContainerColor = DarkSurface,
                        cursorColor = AccentColor,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                )
            }

            // --- "Create Group" Action (Only in 1-on-1 mode) ---
            AnimatedVisibility(visible = !isGroupMode && searchQuery.isEmpty()) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isGroupMode = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(AccentColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.GroupAdd, 
                                "Group", 
                                tint = AccentColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Создать группу",
                            color = AccentColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 80.dp),
                        color = DividerColor,
                        thickness = 0.5.dp
                    )
                }
            }

            // --- List Content ---
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                val listToShow = searchResults

                if (listToShow.isEmpty() && searchQuery.isNotBlank()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.Search, 
                                    null, 
                                    tint = TextSecondary.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Пользователь не найден", 
                                    color = TextSecondary,
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }

                items(listToShow) { user ->
                    val isSelected = selectedUsers.contains(user)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isGroupMode) {
                                    selectedUsers = if (isSelected) {
                                        selectedUsers - user
                                    } else {
                                        selectedUsers + user
                                    }
                                } else {
                                    // Create 1-on-1 chat
                                    scope.launch {
                                        isLoading = true
                                        try {
                                            val participants = listOf(currentUid ?: "", user.uid).sorted()
                                            val chatId = AppGraph.chatRepo.createChat(participants, false, null)
                                            onChatCreated(chatId, user.uid, user.name, false)
                                        } catch (e: Exception) {
                                            // Handle error
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        colors = listOf(Color(0xFF4A90E2), Color(0xFF9B59B6))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (user.photoUrl != null) {
                                // TODO: Load image
                            }
                            Text(
                                text = user.name.firstOrNull()?.toString()?.uppercase() ?: "?",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // Info
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user.name.ifBlank { user.username },
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp
                            )
                            Text(
                                text = user.username,
                                color = TextSecondary,
                                fontSize = 14.sp
                            )
                        }

                        // Checkbox for Group Mode
                        if (isGroupMode) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedUsers = if (checked) {
                                        selectedUsers + user
                                    } else {
                                        selectedUsers - user
                                    }
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AccentColor,
                                    uncheckedColor = TextSecondary,
                                    checkmarkColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        // Loading Overlay
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AccentColor)
            }
        }

        // Group Name Dialog
        if (showGroupNameDialog) {
            AlertDialog(
                onDismissRequest = { showGroupNameDialog = false },
                containerColor = DarkSurface,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary,
                title = { Text("Название группы") },
                text = {
                    OutlinedTextField(
                        value = groupName,
                        onValueChange = { groupName = it },
                        label = { Text("Название") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AccentColor,
                            unfocusedBorderColor = TextSecondary,
                            focusedLabelColor = AccentColor,
                            unfocusedLabelColor = TextSecondary,
                            cursorColor = AccentColor,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (groupName.isNotBlank()) {
                                showGroupNameDialog = false
                                scope.launch {
                                    isLoading = true
                                    try {
                                        val participants = (selectedUsers.map { it.uid } + (currentUid ?: "")).distinct()
                                        val chatId = AppGraph.chatRepo.createChat(participants, true, groupName)
                                        onChatCreated(chatId, "group", groupName, true)
                                    } catch (e: Exception) {
                                        // Handle error
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
                    ) {
                        Text("Создать", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGroupNameDialog = false }) {
                        Text("Отмена", color = TextSecondary)
                    }
                }
            )
        }
    }
}
