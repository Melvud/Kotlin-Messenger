package com.example.messenger_app.push

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object FcmTokenManager {
    suspend fun ensureCurrentTokenRegistered(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val token = FirebaseMessaging.getInstance().token.await()
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(user.uid)
            .update(mapOf("fcmToken" to token))
    }
}
