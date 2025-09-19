@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.messenger_app.ui.contacts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.messenger_app.data.CallInfo
import com.example.messenger_app.data.CallsRepository
import com.example.messenger_app.data.Contact
import com.example.messenger_app.data.ContactsRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(
    onAdd: () -> Unit,
    onGoToCall: (callId: String, callType: String) -> Unit
) {
    val scope = rememberCoroutineScope()

    val auth = remember { FirebaseAuth.getInstance() }
    val db = remember { FirebaseFirestore.getInstance() }
    val contactsRepo = remember { ContactsRepository(auth, db) }
    val callsRepo = remember { CallsRepository(auth, db) }

    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // ---------- Permissions ----------
    val context = LocalContext.current

    fun requiredOnFirstEnter(): List<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list
    }

    fun missingPermissions(perms: List<String>): List<String> =
        perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    var askedOnEnter by remember { mutableStateOf(false) }
    var missingOnEnter by remember { mutableStateOf<List<String>>(emptyList()) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val stillMissing = result.filterValues { granted -> !granted }.keys.toList()
        missingOnEnter = stillMissing
    }

    // Первый заход — спросим всё сразу (камера/микрофон/уведомления)
    LaunchedEffect(Unit) {
        val missing = missingPermissions(requiredOnFirstEnter())
        missingOnEnter = missing
        if (!askedOnEnter && missing.isNotEmpty()) {
            askedOnEnter = true
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }
    // ----------------------------------

    LaunchedEffect(Unit) {
        contactsRepo.contactsFlow().collect { list ->
            contacts = list
            loading = false
            error = null
        }
    }

    val filtered = remember(query, contacts) {
        val q = query.trim()
        if (q.isEmpty()) contacts
        else contacts.filter { it.username.contains(q, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("Контакты") },
                actions = {
                    // ← текстовая кнопка «Добавить контакт»
                    TextButton(onClick = onAdd) {
                        Text("Добавить контакт")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Поиск
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Поиск по нику…") }
            )

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (error != null) {
                Text(
                    text = error!!,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered, key = { it.id }) { item ->
                        ContactCard(
                            username = item.username,
                            onAudio = {
                                val need = listOf(Manifest.permission.RECORD_AUDIO)
                                val miss = missingPermissions(need)
                                if (miss.isNotEmpty()) {
                                    permissionsLauncher.launch(miss.toTypedArray()); return@ContactCard
                                }
                                scope.launch {
                                    startCallAndNavigate(
                                        callsRepo = callsRepo,
                                        remoteUid = item.id,
                                        type = "audio",
                                        onGoToCall = onGoToCall,
                                        onError = { error = it }
                                    )
                                }
                            },
                            onVideo = {
                                val need = listOf(
                                    Manifest.permission.RECORD_AUDIO,
                                    Manifest.permission.CAMERA
                                )
                                val miss = missingPermissions(need)
                                if (miss.isNotEmpty()) {
                                    permissionsLauncher.launch(miss.toTypedArray()); return@ContactCard
                                }
                                scope.launch {
                                    startCallAndNavigate(
                                        callsRepo = callsRepo,
                                        remoteUid = item.id,
                                        type = "video",
                                        onGoToCall = onGoToCall,
                                        onError = { error = it }
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactCard(
    username: String,
    onAudio: () -> Unit,
    onVideo: () -> Unit
) {
    val displayName = remember(username) {
        username.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
    val initial = (displayName.firstOrNull() ?: '?').uppercaseChar().toString()
    val avatarColor = remember(username) { avatarColorFor(username) }

    Card(
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Аватар-кружок с первой буквой
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(avatarColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
            }

            Spacer(Modifier.width(12.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(Modifier.width(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = onAudio) {
                    Icon(Icons.Filled.Call, contentDescription = "Аудио")
                }
                IconButton(onClick = onVideo) {
                    Icon(Icons.Filled.Videocam, contentDescription = "Видео")
                }
            }
        }
    }
}

private fun avatarColorFor(key: String): Color {
    val palette = listOf(
        Color(0xFFE57373),
        Color(0xFF64B5F6),
        Color(0xFF81C784),
        Color(0xFF9575CD),
        Color(0xFFFFB74D),
        Color(0xFF4DB6AC),
        Color(0xFFBA68C8),
        Color(0xFFFF8A65)
    )
    val index = (key.lowercase().hashCode() and 0x7fffffff) % palette.size
    return palette[index]
}

private suspend fun startCallAndNavigate(
    callsRepo: CallsRepository,
    remoteUid: String,
    type: String,
    onGoToCall: (callId: String, callType: String) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val call: CallInfo = callsRepo.startCall(
            calleeUid = remoteUid,
            callType = type
        )
        onGoToCall(call.id, call.callType)
    } catch (e: Exception) {
        onError(e.message ?: "Не удалось начать звонок")
    }
}
