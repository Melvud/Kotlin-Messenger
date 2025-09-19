package com.example.messenger_app

import android.app.Application
import android.provider.Settings
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.firestore
import com.google.firebase.Firebase
import com.google.firebase.messaging.messaging
import com.example.messenger_app.data.*

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        AppGraph.init(this)
    }
}

object AppGraph {
    // Глобальный «граф» — простейший DI
    lateinit var userRepo: UserRepository
        private set
    lateinit var contactsRepo: ContactsRepository
        private set
    lateinit var fcmTokenManager: FcmTokenManager
        private set

    @Volatile private var initialized = false
    val isInitialized: Boolean get() = initialized

    fun init(app: Application) {
        if (initialized) return
        val auth = FirebaseAuth.getInstance()
        val db = Firebase.firestore
        val msg = Firebase.messaging
        val deviceId = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"

        fcmTokenManager = FcmTokenManager(auth, db, msg, deviceId, deviceName)
        userRepo = UserRepository(auth, db, fcmTokenManager)
        contactsRepo = ContactsRepository(auth, db)

        initialized = true
    }
}
