package com.example.messenger_app

import android.app.Application
import android.provider.Settings
import com.example.messenger_app.data.*
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.firestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import io.getstream.chat.android.client.ChatClient
import io.getstream.chat.android.client.logger.ChatLogLevel
import io.getstream.chat.android.offline.plugin.factory.StreamOfflinePluginFactory
import io.getstream.chat.android.state.plugin.config.StatePluginConfig
import io.getstream.chat.android.state.plugin.factory.StreamStatePluginFactory

import coil.ImageLoader
import coil.ImageLoaderFactory

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        AppGraph.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .build()
    }
}

object AppGraph {
    lateinit var userRepo: UserRepository
        private set
    lateinit var chatRepo: ChatRepository
        private set
    lateinit var contactsRepo: ContactsRepository
        private set
    lateinit var fcmTokenManager: FcmTokenManager
        private set
    
    // Stream Client
    lateinit var chatClient: ChatClient
        private set

    @Volatile private var initialized = false
    val isInitialized: Boolean get() = initialized

    fun init(app: Application) {
        if (initialized) return
        
        val auth = FirebaseAuth.getInstance()
        val db = Firebase.firestore
        val msg = Firebase.messaging
        val functions = FirebaseFunctions.getInstance() // Correct way to get instance
        
        val deviceId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        // --- STREAM CHAT INIT ---
        // Только API KEY (Secret Key здесь быть НЕ должно)
        val apiKey = "ph9xg7m6nja5" 
        
        // Stream Chat v6 OfflinePluginFactory configuration
        val offlinePluginFactory = StreamOfflinePluginFactory(app)
        val statePluginFactory = StreamStatePluginFactory(
            config = StatePluginConfig(
                backgroundSyncEnabled = true,
                userPresence = true
            ),
            appContext = app
        )

        chatClient = ChatClient.Builder(apiKey, app)
            .withPlugins(offlinePluginFactory, statePluginFactory) // Use withPlugins
            .logLevel(ChatLogLevel.ALL)
            .build()
        // ------------------------
        // ------------------------

        fcmTokenManager = FcmTokenManager(auth, db, msg, deviceId, deviceName)
        userRepo = UserRepository(auth, db, fcmTokenManager)
        
        // Передаем Functions в репозиторий для получения токена
        chatRepo = ChatRepository(chatClient, auth, functions)
        contactsRepo = ContactsRepository(auth, db)

        initialized = true
    }
}