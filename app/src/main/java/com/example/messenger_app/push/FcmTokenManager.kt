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
            val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            
            val deviceData = mapOf(
                "token" to token,
                "deviceName" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("devices")
                .document(deviceId)
                .set(deviceData, com.google.firebase.firestore.SetOptions.merge())
                .await()
                
            Log.d("FcmTokenManager", "Token registered in devices subcollection: $deviceId")
        }.onFailure { e -> Log.w("FcmTokenManager", "register token failed", e) }
    }

    fun onNewToken(context: Context, token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)

        // 1. Update Firestore
        val deviceData = mapOf(
            "token" to token,
            "deviceName" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )

        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .collection("devices")
            .document(deviceId)
            .set(deviceData, com.google.firebase.firestore.SetOptions.merge())
            .addOnFailureListener { Log.w("FcmTokenManager", "onNewToken failed to update Firestore", it) }
            .addOnSuccessListener { Log.d("FcmTokenManager", "onNewToken updated devices subcollection") }

        // 2. Update Stream Chat
        try {
            val client = io.getstream.chat.android.client.ChatClient.instance()
            if (client.clientState.user.value != null) {
                client.addDevice(
                    io.getstream.chat.android.models.Device(
                        token = token,
                        pushProvider = io.getstream.chat.android.models.PushProvider.FIREBASE,
                        providerName = "firebase"
                    )
                ).enqueue { result ->
                    if (result.isSuccess) {
                        Log.d("FcmTokenManager", "Stream Chat device updated with new token")
                    } else {
                        Log.e("FcmTokenManager", "Failed to update Stream Chat device: ${result.errorOrNull()}")
                    }
                }
            }
        } catch (e: Exception) {
            // ChatClient might not be initialized yet
            Log.w("FcmTokenManager", "ChatClient not ready to update token", e)
        }
    }
}
