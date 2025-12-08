package com.example.messenger_app

import android.app.Application
import android.provider.Settings
import com.example.messenger_app.data.*
import com.example.messenger_app.data.push.PushRepository
import com.example.messenger_app.data.repository.ChatRepository
import com.google.firebase.FirebaseApp

import com.google.firebase.firestore.firestore
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging

import coil.ImageLoader
import coil.ImageLoaderFactory

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        FirebaseApp.initializeApp(this)
        AppGraph.init(this)
        com.example.messenger_app.webrtc.WebRtcCallManager.init(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .build()
    }
}

object AppGraph {
    lateinit var sessionManager: SessionManager
        private set

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



    lateinit var callsRepo: com.example.messenger_app.data.CallsRepository
        private set

    lateinit var litterboxRepo: com.example.messenger_app.data.upload.LitterboxRepository
        private set

    lateinit var encryptedUploadManager: com.example.messenger_app.data.upload.EncryptedUploadManager
        private set

    lateinit var encryptedDownloadManager: com.example.messenger_app.data.upload.EncryptedDownloadManager
        private set

    @Volatile private var initialized = false
    val isInitialized: Boolean get() = initialized

    fun init(app: Application) {
        if (initialized) return
        
        // val auth = FirebaseAuth.getInstance() // Removed
        val db = Firebase.firestore
        val msg = Firebase.messaging
        
        sessionManager = SessionManager(app)

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

        fcmTokenManager = FcmTokenManager(sessionManager, db, msg, deviceId, deviceName)
        userRepo = UserRepository(sessionManager, db, fcmTokenManager)
        
        pushRepo = PushRepository(app)
        
        val database = androidx.room.Room.databaseBuilder(
            app,
            com.example.messenger_app.data.local.AppDatabase::class.java,
            "messenger-db"
        ).fallbackToDestructiveMigration().build()
        
        chatRepo = ChatRepository(db, pushRepo, app, database)
        contactsRepo = ContactsRepository(sessionManager, db)
        callsRepo = com.example.messenger_app.data.CallsRepository(sessionManager, db, pushRepo) // Assuming CallsRepository also needs update


        litterboxRepo = com.example.messenger_app.data.upload.LitterboxRepository()
        encryptedUploadManager = com.example.messenger_app.data.upload.EncryptedUploadManager(app, litterboxRepo)
        encryptedDownloadManager = com.example.messenger_app.data.upload.EncryptedDownloadManager(app)

        initialized = true
    }
}