package com.example.messenger_app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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

    fun callRoute(callId: String, isVideo: Boolean, otherUsername: String): String {
        val encoded = Uri.encode(otherUsername)
        return "call/$callId?isVideo=$isVideo&playRingback=true&otherUsername=$encoded"
    }
}

class MainActivity : ComponentActivity() {

    private val intentEvents = MutableSharedFlow<Intent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentEvents.tryEmit(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppTheme {
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }

                val isAuthed = FirebaseAuth.getInstance().currentUser != null
                val startDest = if (isAuthed) Routes.CHATS_LIST else Routes.AUTH

                LaunchedEffect(Unit) {
                    AppUpdateManager.checkForUpdateAndPrompt(this@MainActivity)
                }

                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { _ ->
                    NavHost(navController = navController, startDestination = startDest) {

                        // Авторизация
                        composable(Routes.AUTH) {
                            AuthScreen(
                                onAuthed = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        runCatching {
                                            FcmTokenManager.ensureCurrentTokenRegistered(applicationContext)
                                        }
                                    }
                                    navController.navigate(Routes.CHATS_LIST) {
                                        popUpTo(Routes.AUTH) { inclusive = true }
                                    }
                                }
                            )
                        }

                        // Список чатов (главный экран)
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
                            val chatId = if (chatIdRaw == "new") null else chatIdRaw
                            val otherUserId = entry.arguments?.getString("otherUserId") ?: return@composable
                            val otherUserName = entry.arguments?.getString("otherUserName") ?: ""

                            ChatScreen(
                                chatId = chatId,
                                otherUserId = otherUserId,
                                otherUserName = otherUserName,
                                onBack = { navController.popBackStack() },
                                onAudioCall = { callId ->
                                    navController.navigate(Routes.callRoute(callId, false, otherUserName))
                                },
                                onVideoCall = { callId ->
                                    navController.navigate(Routes.callRoute(callId, true, otherUserName))
                                }
                            )
                        }

                        // Звонок
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
                                    uriPattern =
                                        "${Routes.CALL_DEEPLINK_BASE}?isVideo={isVideo}&playRingback={playRingback}&otherUsername={otherUsername}"
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

                fun handleIntent(i: Intent) {
                    val action = i.getStringExtra("action")
                    val callId = i.getStringExtra("callId")
                    val type = i.getStringExtra("type") ?: "audio"
                    val fromName = i.getStringExtra("fromUsername") ?: ""

                    val deepId = i.getStringExtra("deeplink_callId")
                    val deepIsVideo = i.getBooleanExtra("deeplink_isVideo", false)
                    val deepUser = i.getStringExtra("deeplink_username") ?: ""

                    when {
                        !callId.isNullOrBlank() && !action.isNullOrBlank() -> {
                            when (action) {
                                "accept" -> {
                                    NotificationHelper.cancelIncomingCall(applicationContext, callId)
                                    CoroutineScope(Dispatchers.IO).launch {
                                        runCatching { callsRepo.hangupOtherDevices(callId) }
                                    }
                                    val isVideo = type.equals("video", ignoreCase = true)
                                    navController.navigate(Routes.callRoute(callId, isVideo, fromName)) {
                                        launchSingleTop = true
                                    }
                                }
                                "decline" -> {
                                    NotificationHelper.cancelIncomingCall(applicationContext, callId)
                                    CoroutineScope(Dispatchers.IO).launch {
                                        runCatching { callsRepo.updateStatus(callId, "declined") }
                                    }
                                }
                            }
                        }
                        !deepId.isNullOrBlank() -> {
                            navController.navigate(Routes.callRoute(deepId, deepIsVideo, deepUser)) {
                                launchSingleTop = true
                            }
                        }
                        else -> {
                            navController.handleDeepLink(i)
                        }
                    }
                }

                LaunchedEffect(Unit) { intent?.let { handleIntent(it) } }
                LaunchedEffect(Unit) { intentEvents.collect { incoming -> handleIntent(incoming) } }

                LaunchedEffect(Unit) {
                    if (FirebaseAuth.getInstance().currentUser != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            runCatching {
                                FcmTokenManager.ensureCurrentTokenRegistered(applicationContext)
                            }
                        }
                    }
                }
            }
        }
    }
}