package com.example.messenger_app

import android.app.Application
import android.provider.Settings
import com.example.messenger_app.data.*
import com.example.messenger_app.data.push.PushRepository
import com.example.messenger_app.data.repository.ChatRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.firestore
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging

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
    lateinit var fcmTokenManager: FcmTokenManager
        private set
    
    lateinit var pushRepo: PushRepository
        private set

    lateinit var chatRepo: ChatRepository
        private set

    lateinit var contactsRepo: ContactsRepository
        private set

    lateinit var userRepo: UserRepository
        private set

    lateinit var fileTransferManager: com.example.messenger_app.data.p2p.FileTransferManager
        private set

    lateinit var callsRepo: com.example.messenger_app.data.CallsRepository
        private set

    @Volatile private var initialized = false
    val isInitialized: Boolean get() = initialized

    fun init(app: Application) {
        if (initialized) return
        
        val auth = FirebaseAuth.getInstance()
        val db = Firebase.firestore
        val msg = Firebase.messaging
        
        var deviceId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        
        // Fallback if ANDROID_ID is null or empty (e.g. emulator or some devices)
        if (deviceId.isNullOrBlank() || deviceId == "9774d56d682e549c") { // Known generic ID
            val prefs = app.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            deviceId = prefs.getString("device_uuid", null)
            if (deviceId == null) {
                deviceId = java.util.UUID.randomUUID().toString()
                prefs.edit().putString("device_uuid", deviceId).apply()
            }
        }

        val deviceName = android.os.Build.MODEL // User requested Build.MODEL

        fcmTokenManager = FcmTokenManager(auth, db, msg, deviceId, deviceName)
        userRepo = UserRepository(auth, db, fcmTokenManager)
        
        pushRepo = PushRepository(app)
        
        val database = androidx.room.Room.databaseBuilder(
            app,
            com.example.messenger_app.data.local.AppDatabase::class.java,
            "messenger-db"
        ).build()
        
        chatRepo = ChatRepository(db, com.google.firebase.storage.FirebaseStorage.getInstance(), pushRepo, app, database)
        contactsRepo = ContactsRepository(auth, db)
        callsRepo = com.example.messenger_app.data.CallsRepository(auth, db, pushRepo)
        fileTransferManager = com.example.messenger_app.data.p2p.FileTransferManager(app, db)

        initialized = true
    }
}