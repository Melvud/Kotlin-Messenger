package com.example.messenger_app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.example.messenger_app.AppGraph
import com.example.messenger_app.data.UserRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    val user = auth.currentUser
    val userRepo = AppGraph.userRepo // Assuming AppGraph has userRepo exposed
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Use collectAsState for real-time updates, fixing the delay
    val userProfile by userRepo.currentUserProfileFlow().collectAsState(initial = null)

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    snackbarHostState.showSnackbar("Загрузка фото...")
                    val url = userRepo.uploadProfilePicture(uri)
                    // Sync with Stream Chat
                    try {
                        // AppGraph.chatRepo.updateStreamUser(displayName, null) // RemovedrProfile?.name ?: "", url)
                    } catch (e: Exception) {
                        // ignore
                    }
                    snackbarHostState.showSnackbar("Фото обновлено")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("Ошибка загрузки: ${e.message}")
                }
            }
        }
    }

    var showNameDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var showEmailDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Профиль", color = Color.White) },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary
                            )
                        )
                    )
                    .clickable {
                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center
            ) {
                if (userProfile?.photoUrl != null) {
                    AsyncImage(
                        model = userProfile?.photoUrl,
                        contentDescription = "Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = userProfile?.name?.firstOrNull()?.uppercase() ?: user?.displayName?.firstOrNull()?.uppercase() ?: "?",
                        fontSize = 48.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Info Cards
            ProfileItem(
                icon = Icons.Default.Person,
                title = "Display Name",
                value = userProfile?.name ?: user?.displayName ?: "Не указано",
                onClick = { showNameDialog = true }
            )

            ProfileItem(
                icon = Icons.Default.Person, // Or another icon like Badge
                title = "Username",
                value = userProfile?.username ?: "Не указано",
                onClick = { showUsernameDialog = true }
            )

            ProfileItem(
                icon = Icons.Default.Email,
                title = "Email",
                value = user?.email ?: "Не указано",
                onClick = { showEmailDialog = true }
            )

            ProfileItem(
                icon = Icons.Default.Lock,
                title = "Пароль",
                value = "********",
                onClick = { showPasswordDialog = true }
            )



            // Logout Button
            Button(
                onClick = {
                    scope.launch {
                        // Disconnect Stream Chat if needed, but usually MainActivity handles it
                        onLogout()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, null)
                Spacer(Modifier.width(8.dp))
                Text("Выйти")
            }
        }
    }

    if (showNameDialog) {
        EditDialog(
            title = "Изменить имя",
            initialValue = userProfile?.name ?: user?.displayName ?: "",
            onDismiss = { showNameDialog = false },
            onConfirm = { newName ->
                scope.launch {
                    try {
                        userRepo.updateProfile(newName = newName)
                        // Sync with Stream Chat
                        // Removed AppGraph.chatRepo.updateStreamUser call
                        showNameDialog = false
                        snackbarHostState.showSnackbar("Имя обновлено")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Ошибка: ${e.message}")
                    }
                }
            }
        )
    }

    if (showUsernameDialog) {
        EditDialog(
            title = "Изменить Username (@...)",
            initialValue = userProfile?.username ?: "",
            onDismiss = { showUsernameDialog = false },
            onConfirm = { newUsername ->
                scope.launch {
                    try {
                         if (!newUsername.startsWith("@")) {
                            throw IllegalArgumentException("Username must start with @")
                        }
                        userRepo.updateProfile(newUsername = newUsername)
                        showUsernameDialog = false
                        snackbarHostState.showSnackbar("Username обновлен")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Ошибка: ${e.message}")
                    }
                }
            }
        )
    }

    if (showEmailDialog) {
        EditDialog(
            title = "Изменить Email",
            initialValue = user?.email ?: "",
            onDismiss = { showEmailDialog = false },
            onConfirm = { newEmail ->
                scope.launch {
                    try {
                        userRepo.updateProfile(newEmail = newEmail)
                        showEmailDialog = false
                        snackbarHostState.showSnackbar("Email обновлен")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Ошибка: ${e.message}. Возможно, требуется повторный вход.")
                    }
                }
            }
        )
    }

    if (showPasswordDialog) {
        EditDialog(
            title = "Изменить пароль",
            initialValue = "",
            isPassword = true,
            onDismiss = { showPasswordDialog = false },
            onConfirm = { newPassword ->
                scope.launch {
                    try {
                        userRepo.updateProfile(newPassword = newPassword)
                        showPasswordDialog = false
                        snackbarHostState.showSnackbar("Пароль обновлен")
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Ошибка: ${e.message}. Возможно, требуется повторный вход.")
                    }
                }
            }
        )
    }
}

@Composable
fun ProfileItem(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Text(text = value, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun EditDialog(
    title: String,
    initialValue: String,
    isPassword: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}
