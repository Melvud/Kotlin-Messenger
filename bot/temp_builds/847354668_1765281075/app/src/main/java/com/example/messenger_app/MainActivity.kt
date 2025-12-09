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
import androidx.compose.runtime.livedata.observeAsState
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.messenger_app.data.CallsRepository
import com.example.messenger_app.data.FcmTokenManager
import com.example.messenger_app.push.NotificationHelper
import com.example.messenger_app.ui.auth.AuthScreen
import com.example.messenger_app.ui.call.CallScreen
import com.example.messenger_app.ui.chat.ChatScreen
// import com.example.messenger_app.ui.chats.ChatsListScreen // Removed
// import com.example.messenger_app.ui.chats.CreateChatScreen // Removed
// import com.example.messenger_app.ui.chats.CreateGroupChatScreen // Removed
import com.example.messenger_app.ui.profile.ProfileScreen
import com.example.messenger_app.ui.theme.AppTheme
import com.example.messenger_app.update.AppUpdateManager

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

object Routes {
    const val AUTH = "auth"
    const val CHATS_LIST = "chats"
    const val CREATE_CHAT = "create_chat"
    const val CREATE_GROUP_CHAT = "create_group_chat"
    const val PROFILE = "profile"
    const val CHAT = "chats/{chatId}/{otherUserId}/{otherUserName}?isGroup={isGroup}"
    const val CALL_ROUTE = "call/{callId}?isVideo={isVideo}&playRingback={playRingback}&otherUsername={otherUsername}"
    const val CALL_DEEPLINK_BASE = "messenger://call/{callId}"
    const val GROUP_INFO = "group_info/{chatId}/{chatName}"
    const val ADD_PARTICIPANT = "add_participant"

    fun chatRoute(chatId: String?, otherUserId: String, otherUserName: String, isGroup: Boolean = false): String {
        val id = chatId ?: "new"
        val encoded = Uri.encode(otherUserName)
        return "chats/$id/$otherUserId/$encoded?isGroup=$isGroup"
    }

    fun callRoute(callId: String, isVideo: Boolean, otherUsername: String, playRingback: Boolean = true): String {
        val encoded = Uri.encode(otherUsername)
        return "call/$callId?isVideo=$isVideo&playRingback=$playRingback&otherUsername=$encoded"
    }

    fun groupInfoRoute(chatId: String, chatName: String): String {
        val encodedName = Uri.encode(chatName)
        return "group_info/$chatId/$encodedName"
    }
}

class MainActivity : ComponentActivity() {

    private val intentEvents = MutableSharedFlow<Intent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var pendingIntent: Intent? = null
    private var permissionsRequested = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        android.util.Log.d("MainActivity", "Permissions result: $permissions")

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

        requestNecessaryPermissions()

        setContent {
            AppTheme {
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }

                val isAuthed = AppGraph.sessionManager.getUid() != null
                val startDest = if (isAuthed) Routes.CHATS_LIST else Routes.AUTH

                LaunchedEffect(Unit) {
                    AppUpdateManager.checkForUpdateAndPrompt(this@MainActivity)
                }

                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { _ ->
                    NavHost(navController = navController, startDestination = startDest) {

                        composable(Routes.AUTH) {
                            AuthScreen(
                                onAuthed = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        runCatching {
                                            AppGraph.fcmTokenManager.registerCurrentToken()
                                            
                                            // Generate and upload Public Key
                                            com.example.messenger_app.utils.KeyManager.generateKeyPair()
                                            val pubKey = com.example.messenger_app.utils.KeyManager.getPublicKey()
                                            if (pubKey != null) {
                                                val uid = AppGraph.sessionManager.getUid()
                                                if (uid != null) {
                                                    FirebaseFirestore.getInstance().collection("users")
                                                        .document(uid)
                                                        .update("publicKey", pubKey)
                                                        .await()
                                                    android.util.Log.d("MainActivity", "Public Key uploaded")
                                                }
                                            }
                                        }.onFailure { e ->
                                            android.util.Log.e("MainActivity", "FCM/Key registration failed", e)
                                        }
                                    }
                                    navController.navigate(Routes.CHATS_LIST) {
                                        popUpTo(Routes.AUTH) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Routes.CHATS_LIST) {
                            com.example.messenger_app.ui.chat.ChatsListScreen(
                                onChatClick = { chatId, otherUserId, otherUserName, isGroup ->
                                    navController.navigate(Routes.chatRoute(chatId, otherUserId, otherUserName, isGroup))
                                },
                                onNewChatClick = {
                                    navController.navigate(Routes.CREATE_CHAT)
                                },
                                onProfileClick = {
                                    navController.navigate(Routes.PROFILE)
                                }
                            )
                        }

                        composable(Routes.CREATE_CHAT) {
                            com.example.messenger_app.ui.chat.CreateChatScreen(
                                onBack = { navController.popBackStack() },
                                onChatCreated = { chatId, otherUserId, otherUserName, isGroup ->
                                    navController.navigate(Routes.chatRoute(chatId, otherUserId, otherUserName, isGroup)) {
                                        popUpTo(Routes.CHATS_LIST)
                                    }
                                }
                            )
                        }

                        /*
                        composable(Routes.CREATE_GROUP_CHAT) {
                            CreateGroupChatScreen(...)
                        }
                        */

                        composable(Routes.PROFILE) {
                            ProfileScreen(
                                onBack = { navController.popBackStack() },
                                onLogout = {
                                    AppGraph.userRepo.signOut()
                                    // AppGraph.chatRepo.disconnectUser() // Removed
                                    navController.navigate(Routes.AUTH) {
                                        popUpTo(Routes.CHATS_LIST) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(
                            route = Routes.CHAT,
                            arguments = listOf(
                                navArgument("chatId") { type = NavType.StringType },
                                navArgument("otherUserId") { type = NavType.StringType },
                                navArgument("otherUserName") { type = NavType.StringType },
                                navArgument("isGroup") { type = NavType.BoolType; defaultValue = false }
                            )
                        ) { entry ->
                            val chatIdRaw = entry.arguments?.getString("chatId") ?: "new"
                            val chatId = if (chatIdRaw == "new" || chatIdRaw.isBlank()) java.util.UUID.randomUUID().toString() else chatIdRaw
                            val otherUserId = entry.arguments?.getString("otherUserId") ?: return@composable
                            val otherUserName = entry.arguments?.getString("otherUserName") ?: ""
                            // val isGroup = entry.arguments?.getBoolean("isGroup") ?: false

                            ChatScreen(
                                chatId = chatId,
                                contactId = otherUserId,
                                contactName = otherUserName,
                                contactToken = "", // TODO: Fetch token
                                onBackClick = { navController.popBackStack() },
                                onNavigateToCall = { callInfo ->
                                    navController.navigate(
                                        Routes.callRoute(
                                            callId = callInfo.id,
                                            isVideo = callInfo.callType == "video",
                                            otherUsername = otherUserName,
                                            playRingback = true
                                        )
                                    )
                                },
                                onGroupInfoClick = {
                                    navController.navigate(Routes.groupInfoRoute(chatId, otherUserName))
                                }
                            )
                        }

                        composable(
                            route = Routes.GROUP_INFO,
                            arguments = listOf(
                                navArgument("chatId") { type = NavType.StringType },
                                navArgument("chatName") { type = NavType.StringType }
                            )
                        ) { entry ->
                            val chatId = entry.arguments?.getString("chatId") ?: return@composable
                            val chatName = entry.arguments?.getString("chatName") ?: ""
                            
                            // We need the SAME ViewModel instance as ChatScreen to share state/logic?
                            // Or just a new one? ChatViewModel requires many params.
                            // Ideally, we should scope the ViewModel to the navigation graph or pass necessary data.
                            // For simplicity, let's create a new ViewModel or use a factory.
                            // But ChatViewModel needs `contactId`, `currentUserId` etc.
                            // `GroupInfoScreen` mainly needs `chatId` and `ChatRepository`.
                            // Let's instantiate a new ChatViewModel. It's a bit heavy but works.
                            // Wait, `ChatViewModel` init loads messages. We don't need that for GroupInfo.
                            // Maybe `GroupInfoScreen` should use a separate `GroupInfoViewModel` or just `ChatRepository` directly?
                            // But `GroupInfoScreen` uses `viewModel.participants`.
                            // Let's use `ChatViewModel` but we need to provide all params.
                            
                            val currentUserId = AppGraph.sessionManager.getUid() ?: ""
                            val currentUserName = AppGraph.sessionManager.getUserName() ?: "" // Need to fetch or store
                            
                            // We can get the ViewModel from a factory or Hilt. Manual DI here is painful.
                            // Let's assume we can create it.
                            val viewModel = remember {
                                com.example.messenger_app.ui.chat.ChatViewModel(
                                    chatRepository = AppGraph.chatRepo,
                                    chatId = chatId,
                                    currentUserId = currentUserId,
                                    currentUserName = currentUserName, // This might be empty if not stored in session manager properly
                                    contactId = "group", // Not relevant for group info
                                    targetUserToken = "",
                                    callsRepository = AppGraph.callsRepo,
                                    encryptedUploadManager = AppGraph.encryptedUploadManager,
                                    encryptedDownloadManager = AppGraph.encryptedDownloadManager
                                )
                            }
                            
                            // Handle result from AddParticipantScreen
                            val savedStateHandle = entry.savedStateHandle
                            val newParticipantId by savedStateHandle.getLiveData<String>("new_participant_id").observeAsState()
                            
                            LaunchedEffect(newParticipantId) {
                                newParticipantId?.let { userId: String ->
                                    viewModel.addParticipant(userId)
                                    savedStateHandle.remove<String>("new_participant_id")
                                }
                            }

                            com.example.messenger_app.ui.chat.GroupInfoScreen(
                                chatId = chatId,
                                chatName = chatName,
                                viewModel = viewModel,
                                onBackClick = { navController.popBackStack() },
                                onAddParticipantClick = {
                                    navController.navigate(Routes.ADD_PARTICIPANT)
                                }
                            )
                        }

                        composable(Routes.ADD_PARTICIPANT) {
                            com.example.messenger_app.ui.chat.AddParticipantScreen(
                                onBack = { navController.popBackStack() },
                                onUserSelected = { userId ->
                                    navController.previousBackStackEntry?.savedStateHandle?.set("new_participant_id", userId)
                                    navController.popBackStack()
                                }
                            )
                        }

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

                // val callsRepo = remember {
                //    CallsRepository(FirebaseAuth.getInstance(), FirebaseFirestore.getInstance(), AppGraph.pushRepo)
                // }
                val callsRepo = AppGraph.callsRepo

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
                    val deepPlayRingback = i.getBooleanExtra("deeplink_playRingback", false)

                    val openChat = action == "open_chat"
                    val chatIdToOpen = i.getStringExtra("chatId")
                    val otherUserIdToOpen = i.getStringExtra("otherUserId")
                    val otherUserNameToOpen = i.getStringExtra("otherUserName")
                    val isGroupChat = i.getBooleanExtra("isGroup", false)

                    when {
                        action == "accept" && !callId.isNullOrBlank() -> {
                            val isVideo = isVideoFromIntent || type.equals("video", ignoreCase = true)
                            android.util.Log.d("MainActivity", "Accept call: callId=$callId, isVideo=$isVideo")

                            if (!checkCallPermissions(includeCamera = isVideo)) {
                                android.util.Log.d("MainActivity", "Permissions not granted, saving intent")
                                pendingIntent = i
                                requestCallPermissions(includeCamera = isVideo)
                                return
                            }

                            android.util.Log.d("MainActivity", "Permissions OK, navigating to CallScreen")

                            NotificationHelper.cancelIncomingCall(applicationContext, callId)

                            navController.navigate(
                                Routes.callRoute(callId, isVideo, fromName, playRingback = false)
                            ) {
                                launchSingleTop = true
                            }
                        }

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

                        openChat && !chatIdToOpen.isNullOrBlank() -> {
                            android.util.Log.d("MainActivity", "Open chat: chatId=$chatIdToOpen, isGroup=$isGroupChat")
                            val userName = otherUserNameToOpen ?: "Chat"
                            val userId = if (otherUserIdToOpen.isNullOrBlank()) "group" else otherUserIdToOpen
                            
                            navController.navigate(Routes.chatRoute(chatIdToOpen, userId, userName, isGroupChat)) {
                                launchSingleTop = true
                            }
                        }

                        !deepId.isNullOrBlank() -> {
                            android.util.Log.d("MainActivity", "Deep link call: callId=$deepId, isVideo=$deepIsVideo, playRingback=$deepPlayRingback")
                            navController.navigate(
                                Routes.callRoute(deepId, deepIsVideo, deepUser, playRingback = deepPlayRingback)
                            ) {
                                launchSingleTop = true
                            }
                        }

                        else -> {
                            android.util.Log.d("MainActivity", "Handling generic deep link")
                            navController.handleDeepLink(i)
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    intent?.let {
                        android.util.Log.d("MainActivity", "Processing initial intent")
                        handleIntent(it)
                    }
                }

                LaunchedEffect(Unit) {
                    intentEvents.collect { incoming ->
                        android.util.Log.d("MainActivity", "Processing new intent from flow")
                        handleIntent(incoming)
                    }
                }

                LaunchedEffect(Unit) {
                    if (AppGraph.sessionManager.getUid() != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            runCatching {
                                AppGraph.fcmTokenManager.registerCurrentToken()
                                
                                // Generate and upload Public Key
                                com.example.messenger_app.utils.KeyManager.generateKeyPair()
                                val pubKey = com.example.messenger_app.utils.KeyManager.getPublicKey()
                                if (pubKey != null) {
                                    val uid = AppGraph.sessionManager.getUid()
                                    if (uid != null) {
                                        FirebaseFirestore.getInstance().collection("users")
                                            .document(uid)
                                            .update("publicKey", pubKey)
                                            .await()
                                        android.util.Log.d("MainActivity", "Public Key uploaded")
                                    }
                                }
                            }.onFailure { e ->
                                android.util.Log.e("MainActivity", "FCM/Key registration failed", e)
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

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }

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

    override fun onResume() {
        super.onResume()
        CoroutineScope(Dispatchers.IO).launch {
            AppGraph.userRepo.updateOnlineStatus(true)
        }
    }

    override fun onPause() {
        super.onPause()
        CoroutineScope(Dispatchers.IO).launch {
            AppGraph.userRepo.updateOnlineStatus(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("MainActivity", "onDestroy")
    }
}