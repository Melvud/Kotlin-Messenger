package com.example.messenger_app.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

data class CallInfo(
    val id: String,
    val callerUid: String,
    val calleeUid: String,
    val callType: String // "audio" | "video"
)

class CallsRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore
) {

    /**
     * Создаёт документ звонка и СРАЗУ шлёт push-уведомление адресату через callable
     * Cloud Function "sendCallNotification".
     */
    suspend fun startCall(calleeUid: String, callType: String): CallInfo {
        val me = auth.currentUser?.uid ?: error("Not authorized")

        // 1) создаём документ звонка
        val callRef = db.collection("calls").document()
        val body = mapOf(
            "id" to callRef.id,
            "callerUid" to me,
            "calleeUid" to calleeUid,
            "callType" to callType,
            "status" to "ringing",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        callRef.set(body).await()

        // 2) определим, как нас показывать адресату
        val meSnap = runCatching { db.collection("users").document(me).get().await() }.getOrNull()
        val fromUsername =
            meSnap?.getString("username")
                ?: meSnap?.getString("name")
                ?: me // пусть будет uid, если профиля нет

        // 3) вызовем функцию отправки пуша
        val data = mapOf(
            "toUserId" to calleeUid,
            "fromUsername" to fromUsername,
            "callId" to callRef.id,
            "callType" to callType
        )
        runCatching {
            FirebaseFunctions.getInstance()
                .getHttpsCallable("sendCallNotification")
                .call(data)
                .await()
        }.onFailure { e ->
            Log.w("CallsRepository", "sendCallNotification failed: ${e.message}", e)
        }

        return CallInfo(callRef.id, me, calleeUid, callType)
    }

    suspend fun updateStatus(callId: String, status: String) {
        db.collection("calls").document(callId)
            .update(
                mapOf(
                    "status" to status,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    suspend fun setOffer(callId: String, sdp: String, type: String = "offer") {
        db.collection("calls").document(callId)
            .update(
                mapOf(
                    "offer" to mapOf("type" to type, "sdp" to sdp),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    suspend fun setAnswer(callId: String, sdp: String, type: String = "answer") {
        db.collection("calls").document(callId)
            .update(
                mapOf(
                    "answer" to mapOf("type" to type, "sdp" to sdp),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    fun candidatesCollection(callId: String, who: String) =
        db.collection("calls").document(callId).collection(
            when (who) {
                "caller" -> "callerCandidates"
                else -> "calleeCandidates"
            }
        )

    /**
     * Сообщить другим устройствам того же пользователя, что этот экземпляр принял звонок
     * (вызывается в CallActionReceiver / MainActivity).
     */
    suspend fun hangupOtherDevices(callId: String) {
        val acceptedToken = FirebaseMessaging.getInstance().token.await()
        val data = mapOf(
            "callId" to callId,
            "acceptedToken" to acceptedToken
        )
        FirebaseFunctions.getInstance()
            .getHttpsCallable("hangupOtherDevices")
            .call(data)
            .await()
    }
}
