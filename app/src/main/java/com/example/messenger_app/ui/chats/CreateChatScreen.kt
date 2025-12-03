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
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Search
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
import com.example.messenger_app.AppGraph
import com.example.messenger_app.data.Contact
import com.example.messenger_app.data.ContactsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateChatScreen(
    onBack: () -> Unit,
    onContactSelected: (String, String, String) -> Unit, // cid, userId, userName
    onCreateGroup: () -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val contactsRepo = remember { ContactsRepository(auth, db) }
    val chatRepo = AppGraph.chatRepo
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var myContacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Load existing contacts
    LaunchedEffect(Unit) {
        contactsRepo.contactsFlow().collect {
            myContacts = it
        }
    }

    // Search logic
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
                title = { Text("Новый чат", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Поиск людей...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // New Group Option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCreateGroup)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.GroupAdd, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Text("Создать группу", style = MaterialTheme.typography.titleMedium)
            }

            Divider()

            // List
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (searchQuery.isNotBlank()) {
                    item {
                        Text(
                            "Результаты поиска",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(searchResults) { contact ->
                        ContactItem(contact) {
                            scope.launch {
                                val cid = chatRepo.createDirectChat(contact.id)
                                if (cid != null) {
                                    onContactSelected(cid, contact.id, contact.name)
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            "Ваши контакты",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(myContacts) { contact ->
                        ContactItem(contact) {
                            scope.launch {
                                val cid = chatRepo.createDirectChat(contact.id)
                                if (cid != null) {
                                    onContactSelected(cid, contact.id, contact.name)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactItem(contact: Contact, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
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
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(contact.name, style = MaterialTheme.typography.bodyLarge)
            if (contact.username.isNotBlank()) {
                Text(contact.username, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}
