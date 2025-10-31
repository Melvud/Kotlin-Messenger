package com.example.messenger_app.push

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object FcmTokenManager {
    suspend fun ensureCurrentTokenRegistered(context: Context) {
        runCatching {
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            val token = FirebaseMessaging.getInstance().token.await()
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .update(mapOf("fcmToken" to token))
                .await()
        }.onFailure { e -> Log.w("FcmTokenManager", "register token failed", e) }
    }

    fun onNewToken(context: Context, token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .update(mapOf("fcmToken" to token))
            .addOnFailureListener { Log.w("FcmTokenManager", "onNewToken failed", it) }
    }
}
