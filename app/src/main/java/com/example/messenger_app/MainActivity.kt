package com.example.messenger_app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.messenger_app.data.CallsRepository
import com.example.messenger_app.push.FcmTokenManager
import com.example.messenger_app.push.NotificationHelper
import com.example.messenger_app.ui.auth.AuthScreen
import com.example.messenger_app.ui.call.CallScreen
import com.example.messenger_app.ui.chats.ChatScreen
import com.example.messenger_app.ui.chats.ChatsListScreen
import com.example.messenger_app.ui.theme.AppTheme
import com.example.messenger_app.update.AppUpdateManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

object Routes {
    const val AUTH = "auth"
    const val CHATS_LIST = "chats"
    const val CHAT = "chats/{chatId}/{otherUserId}/{otherUserName}"
    const val CALL_ROUTE = "call/{callId}?isVideo={isVideo}&playRingback={playRingback}&otherUsername={otherUsername}"
    const val CALL_DEEPLINK_BASE = "messenger://call/{callId}"

    fun chatRoute(chatId: String?, otherUserId: String, otherUserName: String): String {
        val id = chatId ?: "new"
        val encoded = Uri.encode(otherUserName)
        return "chats/$id/$otherUserId/$encoded"
    }

    fun callRoute(callId: String, isVideo: Boolean, otherUsername: String, playRingback: Boolean = true): String {
        val encoded = Uri.encode(otherUsername)
        return "call/$callId?isVideo=$isVideo&playRingback=$playRingback&otherUsername=$encoded"
    }
}

class MainActivity : ComponentActivity() {

    private val intentEvents = MutableSharedFlow<Intent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Хранилище для отложенного intent после получения разрешений
    private var pendingIntent: Intent? = null
    private var permissionsRequested = false

    // Лаунчер для запроса разрешений
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        android.util.Log.d("MainActivity", "Permissions result: $permissions")

        // Обрабатываем отложенный intent если все разрешения получены
        val allGranted = permissions.values.all { it }
        if (allGranted && pendingIntent != null) {
            android.util.Log.d("MainActivity", "All permissions granted, processing pending intent")
            val intent = pendingIntent
            pendingIntent = null
            intent?.let { intentEvents.tryEmit(it) }
        } else if (!allGranted) {
            android.util.Log.w("MainActivity", "Some permissions denied: ${permissions.filter { !it.value }}")
            pendingIntent = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.util.Log.d("MainActivity", "onNewIntent: action=${intent.action}, extras=${intent.extras?.keySet()}")
        setIntent(intent)
        intentEvents.tryEmit(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("MainActivity", "onCreate")

        // Запрашиваем необходимые разрешения при старте
        requestNecessaryPermissions()

        setContent {
            AppTheme {
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }

                val isAuthed = FirebaseAuth.getInstance().currentUser != null
                val startDest = if (isAuthed) Routes.CHATS_LIST else Routes.AUTH

                // Проверка обновлений
                LaunchedEffect(Unit) {
                    AppUpdateManager.checkForUpdateAndPrompt(this@MainActivity)
                }

                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { _ ->
                    NavHost(navController = navController, startDestination = startDest) {

                        // Экран авторизации
                        composable(Routes.AUTH) {
                            AuthScreen(
                                onAuthed = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        runCatching {
                                            FcmTokenManager.ensureCurrentTokenRegistered(applicationContext)
                                        }.onFailure { e ->
                                            android.util.Log.e("MainActivity", "FCM token registration failed", e)
                                        }
                                    }
                                    navController.navigate(Routes.CHATS_LIST) {
                                        popUpTo(Routes.AUTH) { inclusive = true }
                                    }
                                }
                            )
                        }

                        // Список чатов
                        composable(Routes.CHATS_LIST) {
                            ChatsListScreen(
                                onChatClick = { chatId, otherUserId, otherUserName ->
                                    navController.navigate(Routes.chatRoute(chatId, otherUserId, otherUserName))
                                },
                                onLogout = {
                                    FirebaseAuth.getInstance().signOut()
                                    navController.navigate(Routes.AUTH) {
                                        popUpTo(Routes.CHATS_LIST) { inclusive = true }
                                    }
                                }
                            )
                        }

                        // Экран чата
                        composable(
                            route = Routes.CHAT,
                            arguments = listOf(
                                navArgument("chatId") { type = NavType.StringType },
                                navArgument("otherUserId") { type = NavType.StringType },
                                navArgument("otherUserName") { type = NavType.StringType }
                            )
                        ) { entry ->
                            val chatIdRaw = entry.arguments?.getString("chatId") ?: "new"
                            val chatId = if (chatIdRaw == "new" || chatIdRaw.isBlank()) null else chatIdRaw
                            val otherUserId = entry.arguments?.getString("otherUserId") ?: return@composable
                            val otherUserName = entry.arguments?.getString("otherUserName") ?: ""

                            ChatScreen(
                                chatId = chatId,
                                otherUserId = otherUserId,
                                otherUserName = otherUserName,
                                onBack = { navController.popBackStack() },
                                onAudioCall = { callId ->
                                    if (checkCallPermissions(includeCamera = false)) {
                                        navController.navigate(
                                            Routes.callRoute(callId, false, otherUserName, playRingback = true)
                                        )
                                    } else {
                                        android.util.Log.w("MainActivity", "Audio call permissions not granted")
                                        CoroutineScope(Dispatchers.Main).launch {
                                            snackbarHostState.showSnackbar("Необходимо разрешение на микрофон")
                                        }
                                        requestCallPermissions(includeCamera = false)
                                    }
                                },
                                onVideoCall = { callId ->
                                    if (checkCallPermissions(includeCamera = true)) {
                                        navController.navigate(
                                            Routes.callRoute(callId, true, otherUserName, playRingback = true)
                                        )
                                    } else {
                                        android.util.Log.w("MainActivity", "Video call permissions not granted")
                                        CoroutineScope(Dispatchers.Main).launch {
                                            snackbarHostState.showSnackbar("Необходимы разрешения на камеру и микрофон")
                                        }
                                        requestCallPermissions(includeCamera = true)
                                    }
                                }
                            )
                        }

                        // Экран звонка
                        composable(
                            route = Routes.CALL_ROUTE,
                            arguments = listOf(
                                navArgument("callId") { type = NavType.StringType },
                                navArgument("isVideo") { type = NavType.BoolType; defaultValue = false },
                                navArgument("playRingback") { type = NavType.BoolType; defaultValue = false },
                                navArgument("otherUsername") { type = NavType.StringType; defaultValue = "" }
                            ),
                            deepLinks = listOf(
                                navDeepLink {
                                    uriPattern = "${Routes.CALL_DEEPLINK_BASE}?isVideo={isVideo}&playRingback={playRingback}&otherUsername={otherUsername}"
                                }
                            )
                        ) { entry ->
                            val callId = entry.arguments?.getString("callId").orEmpty()
                            val isVideo = entry.arguments?.getBoolean("isVideo") ?: false
                            val playRingback = entry.arguments?.getBoolean("playRingback") ?: false
                            val otherUsername = entry.arguments?.getString("otherUsername").orEmpty()

                            CallScreen(
                                callId = callId,
                                isVideo = isVideo,
                                playRingback = playRingback,
                                otherUsername = otherUsername.ifBlank { null },
                                onNavigateBack = {
                                    val popped = navController.popBackStack(
                                        route = Routes.CHATS_LIST,
                                        inclusive = false
                                    )
                                    if (!popped) {
                                        navController.navigate(Routes.CHATS_LIST) {
                                            popUpTo(0)
                                            launchSingleTop = true
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                val callsRepo = remember {
                    CallsRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance())
                }

                // Функция обработки Intent
                fun handleIntent(i: Intent) {
                    android.util.Log.d("MainActivity", "handleIntent: action=${i.action}, extras=${i.extras?.keySet()}")

                    val action = i.getStringExtra("action")
                    val callId = i.getStringExtra("callId")
                    val type = i.getStringExtra("type") ?: "audio"
                    val fromName = i.getStringExtra("username") ?: ""
                    val isVideoFromIntent = i.getBooleanExtra("isVideo", false)

                    val deepId = i.getStringExtra("deeplink_callId")
                    val deepIsVideo = i.getBooleanExtra("deeplink_isVideo", false)
                    val deepUser = i.getStringExtra("deeplink_username") ?: ""
                    val deepPlayRingback = i.getBooleanExtra("deeplink_playRingback", true)

                    val openChat = action == "open_chat"
                    val chatIdToOpen = i.getStringExtra("chatId")
                    val otherUserIdToOpen = i.getStringExtra("otherUserId")
                    val otherUserNameToOpen = i.getStringExtra("otherUserName")

                    when {
                        // Принятие звонка
                        action == "accept" && !callId.isNullOrBlank() -> {
                            val isVideo = isVideoFromIntent || type.equals("video", ignoreCase = true)
                            android.util.Log.d("MainActivity", "Accept call: callId=$callId, isVideo=$isVideo")

                            // Проверяем разрешения
                            if (!checkCallPermissions(includeCamera = isVideo)) {
                                android.util.Log.d("MainActivity", "Permissions not granted, saving intent")
                                pendingIntent = i
                                requestCallPermissions(includeCamera = isVideo)
                                return
                            }

                            android.util.Log.d("MainActivity", "Permissions OK, navigating to CallScreen")

                            // Отменяем уведомление
                            NotificationHelper.cancelIncomingCall(applicationContext, callId)

                            // Навигация на экран звонка (входящий звонок - без ringback)
                            navController.navigate(
                                Routes.callRoute(callId, isVideo, fromName, playRingback = false)
                            ) {
                                launchSingleTop = true
                            }
                        }

                        // Отклонение звонка
                        action == "decline" && !callId.isNullOrBlank() -> {
                            android.util.Log.d("MainActivity", "Decline call: callId=$callId")
                            NotificationHelper.cancelIncomingCall(applicationContext, callId)
                            CoroutineScope(Dispatchers.IO).launch {
                                runCatching {
                                    callsRepo.updateStatus(callId, "declined")
                                }.onFailure { e ->
                                    android.util.Log.e("MainActivity", "Failed to update call status", e)
                                }
                            }
                        }

                        // Открытие чата из уведомления
                        openChat && !chatIdToOpen.isNullOrBlank() && !otherUserIdToOpen.isNullOrBlank() -> {
                            android.util.Log.d("MainActivity", "Open chat: chatId=$chatIdToOpen")
                            val userName = otherUserNameToOpen ?: "User"
                            navController.navigate(Routes.chatRoute(chatIdToOpen, otherUserIdToOpen, userName)) {
                                launchSingleTop = true
                            }
                        }

                        // Deep link для звонка
                        !deepId.isNullOrBlank() -> {
                            android.util.Log.d("MainActivity", "Deep link call: callId=$deepId, isVideo=$deepIsVideo, playRingback=$deepPlayRingback")
                            navController.navigate(Routes.callRoute(deepId, deepIsVideo, deepUser, deepPlayRingback)) {
                                launchSingleTop = true
                            }
                        }

                        // Обработка других deep links
                        else -> {
                            android.util.Log.d("MainActivity", "Handling generic deep link")
                            navController.handleDeepLink(i)
                        }
                    }
                }

                // Обрабатываем начальный intent
                LaunchedEffect(Unit) {
                    intent?.let {
                        android.util.Log.d("MainActivity", "Processing initial intent")
                        handleIntent(it)
                    }
                }

                // Обрабатываем новые intents
                LaunchedEffect(Unit) {
                    intentEvents.collect { incoming ->
                        android.util.Log.d("MainActivity", "Processing new intent from flow")
                        handleIntent(incoming)
                    }
                }

                // Регистрируем FCM токен при авторизации
                LaunchedEffect(Unit) {
                    if (FirebaseAuth.getInstance().currentUser != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            runCatching {
                                FcmTokenManager.ensureCurrentTokenRegistered(applicationContext)
                            }.onFailure { e ->
                                android.util.Log.e("MainActivity", "FCM token registration failed", e)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Запрашивает все необходимые разрешения при старте приложения
     */
    private fun requestNecessaryPermissions() {
        if (permissionsRequested) {
            android.util.Log.d("MainActivity", "Permissions already requested, skipping")
            return
        }

        val permissions = mutableListOf<String>()

        // Микрофон для звонков
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        // Камера для видео звонков
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }

        // Уведомления для Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            android.util.Log.d("MainActivity", "Requesting permissions: $permissions")
            permissionsRequested = true
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            android.util.Log.d("MainActivity", "All permissions already granted")
        }
    }

    /**
     * Проверяет наличие разрешений для звонка
     */
    private fun checkCallPermissions(includeCamera: Boolean = false): Boolean {
        val audioGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val cameraGranted = if (includeCamera) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val result = audioGranted && cameraGranted
        android.util.Log.d("MainActivity", "checkCallPermissions(includeCamera=$includeCamera): audio=$audioGranted, camera=$cameraGranted, result=$result")
        return result
    }

    /**
     * Запрашивает разрешения для звонка
     */
    private fun requestCallPermissions(includeCamera: Boolean = false) {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (includeCamera && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (permissions.isNotEmpty()) {
            android.util.Log.d("MainActivity", "Requesting call permissions: $permissions")
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            android.util.Log.d("MainActivity", "Call permissions already granted")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("MainActivity", "onDestroy")
    }
}