package com.example.messenger_app

import android.content.Intent
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
import com.example.messenger_app.ui.contacts.AddContactScreen
import com.example.messenger_app.ui.contacts.ContactsScreen
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
    const val CONTACTS = "contacts"
    const val ADD_CONTACT = "contacts/add"
    const val CALL_ROUTE = "call/{callId}?isVideo={isVideo}&playRingback={playRingback}"
    const val CALL_DEEPLINK_BASE = "messenger://call/{callId}"
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
                val startDest = if (isAuthed) Routes.CONTACTS else Routes.AUTH

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
                                            FcmTokenManager.ensureCurrentTokenRegistered(applicationContext)
                                        }
                                    }
                                    navController.navigate(Routes.CONTACTS) {
                                        popUpTo(Routes.AUTH) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Routes.CONTACTS) {
                            ContactsScreen(
                                onAdd = { navController.navigate(Routes.ADD_CONTACT) },
                                onGoToCall = { callId: String, callType: String ->
                                    val isVideo = callType.equals("video", ignoreCase = true)
                                    navController.navigate("call/$callId?isVideo=$isVideo&playRingback=true")
                                }
                            )
                        }

                        composable(Routes.ADD_CONTACT) {
                            AddContactScreen(onDone = { navController.popBackStack() })
                        }

                        composable(
                            route = Routes.CALL_ROUTE,
                            arguments = listOf(
                                navArgument("callId") { type = NavType.StringType },
                                navArgument("isVideo") { type = NavType.BoolType; defaultValue = false },
                                navArgument("playRingback") { type = NavType.BoolType; defaultValue = false }
                            ),
                            deepLinks = listOf(
                                navDeepLink {
                                    uriPattern =
                                        "${Routes.CALL_DEEPLINK_BASE}?isVideo={isVideo}&playRingback={playRingback}"
                                }
                            )
                        ) { entry ->
                            val callId = entry.arguments?.getString("callId").orEmpty()
                            val isVideo = entry.arguments?.getBoolean("isVideo") ?: false
                            val playRingback = entry.arguments?.getBoolean("playRingback") ?: false

                            CallScreen(
                                callId = callId,
                                isVideo = isVideo,
                                playRingback = playRingback,
                                onNavigateBack = {
                                    // ГАРАНТИРОВАННО уводим на список контактов
                                    val popped = navController.popBackStack(
                                        route = Routes.CONTACTS,
                                        inclusive = false
                                    )
                                    if (!popped) {
                                        navController.navigate(Routes.CONTACTS) {
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

                    if (!callId.isNullOrBlank() && !action.isNullOrBlank()) {
                        when (action) {
                            "accept" -> {
                                NotificationHelper.cancelIncomingCall(applicationContext, callId)
                                CoroutineScope(Dispatchers.IO).launch {
                                    runCatching { callsRepo.hangupOtherDevices(callId) }
                                }
                                val isVideo = type.equals("video", ignoreCase = true)
                                navController.navigate("call/$callId?isVideo=$isVideo&playRingback=false") {
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
                    } else {
                        navController.handleDeepLink(i)
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
