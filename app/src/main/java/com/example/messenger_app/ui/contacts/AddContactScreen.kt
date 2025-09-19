@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.messenger_app.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.messenger_app.data.ContactsRepository
import com.example.messenger_app.data.UserProfile
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class UiUser(
    val uid: String,
    val username: String
)

@Composable
fun AddContactScreen(
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val contactsRepo = remember { ContactsRepository(auth, db) }

    var query by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<UiUser>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var searchJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(query) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isBlank()) {
            searchResults = emptyList()
            error = null
            loading = false
            return@LaunchedEffect
        }
        searchJob = launch {
            loading = true; error = null
            delay(200)
            contactsRepo.searchUsersByUsernameFlow(q).collect { list: List<UserProfile> ->
                searchResults = list.map { UiUser(it.uid, it.username) }
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("Добавить контакт") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Юзернейм (username)") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(8.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(searchResults, key = { it.uid }) { user ->
                    ResultRow(
                        user = user,
                        onAdd = {
                            loading = true; error = null
                            scope.launch {
                                try {
                                    contactsRepo.addContactMutualByUid(user.uid)
                                    loading = false
                                    onDone()
                                } catch (e: Exception) {
                                    loading = false
                                    error = e.message ?: "Не удалось добавить контакт"
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    user: UiUser,
    onAdd: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clickable { /* опционально: открыть профиль */ },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    user.username.ifBlank { "user" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(
                onClick = onAdd,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить")
            }
        }
    }
}
