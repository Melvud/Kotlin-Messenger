package com.example.messenger_app.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.example.messenger_app.data.push.PushRepository

data class CallInfo(
    val id: String,
    val callerUid: String,
    val calleeUid: String,
    val callType: String // "audio" | "video"
)



class CallsRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val pushRepository: PushRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    /**
     * ИСПРАВЛЕНО: Проверяем, что не звоним сами себе
     * ✅ ИЗМЕНЕНИЕ: Функция больше не 'suspend' и возвращает ID немедленно.
     * Вся сетевая работа выполняется в фоновой 'scope.launch'.
     */
    fun startCall(calleeUid: String, callType: String): CallInfo {
        val me = auth.currentUser?.uid ?: error("Not authorized")

        // Проверка: нельзя звонить самому себе
        if (me == calleeUid) {
            error("Cannot call yourself")
        }

        // 1) Генерируем ID звонка локально
        val callRef = db.collection("calls").document()
        val callId = callRef.id

        // 2) Запускаем всю сетевую работу в фоновой корутине
        scope.launch {
            try {
                // 2a) создаём документ звонка
                val body = mapOf(
                    "id" to callId,
                    "callerUid" to me,
                    "calleeUid" to calleeUid,
                    "callType" to callType,
                    "status" to "ringing",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                callRef.set(body).await()

                // 2b) определим, как нас показывать адресату
                val meSnap = runCatching { db.collection("users").document(me).get().await() }.getOrNull()
                val fromUsername =
                    meSnap?.getString("username")
                        ?: meSnap?.getString("name")
                        ?: "Пользователь"

                // 2c) отправляем пуш ТОЛЬКО адресату
                val tokensSnapshot = db.collection("users").document(calleeUid).collection("devices").get().await()
                val tokens = tokensSnapshot.documents.mapNotNull { it.getString("token") }

                tokens.forEach { token ->
                    pushRepository.sendDirectPush(
                        targetToken = token,
                        title = null, // Data-only push for calls to ensure onMessageReceived is called
                        body = null,
                        data = mapOf(
                            "type" to "call",
                            "callId" to callId,
                            "callType" to callType,
                            "callerUid" to me,
                            "fromUserId" to me, // Added for compatibility
                            "toUserId" to calleeUid, // Added for compatibility
                            "callerName" to fromUsername,
                            "fromUsername" to fromUsername, // Added for compatibility
                            "isVideo" to (callType == "video").toString()
                        )
                    )
                }

                Log.d("CallsRepository", "Sent call notifications to ${tokens.size} devices")

            } catch (e: Exception) {
                Log.e("CallsRepository", "Failed to start call in background", e)
                // Опционально: обновить статус, если что-то пошло не так
                runCatching { updateStatus(callId, "failed_to_start") }
            }
        }

        // 3) Немедленно возвращаем ID для быстрой навигации
        return CallInfo(callId, me, calleeUid, callType)
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

    suspend fun setOffer(callId: String, sdp: String, senderRole: String, type: String = "offer") {
        db.collection("calls").document(callId)
            .update(
                mapOf(
                    "offer" to mapOf(
                        "type" to type,
                        "sdp" to sdp,
                        "timestamp" to FieldValue.serverTimestamp(),
                        "senderRole" to senderRole // ✅ ИСПРАВЛЕНО: Добавляем роль отправителя
                    ),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
    }

    suspend fun setAnswer(callId: String, sdp: String, senderRole: String, type: String = "answer") {
        db.collection("calls").document(callId)
            .update(
                mapOf(
                    "answer" to mapOf(
                        "type" to type,
                        "sdp" to sdp,
                        "timestamp" to FieldValue.serverTimestamp(),
                        "senderRole" to senderRole // ✅ ИСПРАВЛЕНО: Добавляем роль отправителя
                    ),
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
     */
    suspend fun acceptCall(callId: String) {
        updateStatus(callId, "answered")
        hangupOtherDevices(callId)
    }

    /**
     * Сообщить другим устройствам того же пользователя, что этот экземпляр принял звонок
     */
    suspend fun hangupOtherDevices(callId: String) {
        val me = auth.currentUser?.uid ?: return
        val currentToken = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            Log.e("CallsRepository", "Failed to get current token", e)
            return
        }

        val tokensSnapshot = db.collection("users").document(me).collection("devices").get().await()
        val otherTokens = tokensSnapshot.documents.mapNotNull { it.getString("token") }
            .filter { it != currentToken }

        otherTokens.forEach { token ->
            pushRepository.sendDirectPush(
                targetToken = token,
                title = null,
                body = null,
                data = mapOf(
                    "type" to "hangup",
                    "callId" to callId,
                    "reason" to "answered_on_another_device"
                )
            )
        }
        Log.d("CallsRepository", "Sent hangup to ${otherTokens.size} other devices")
    }
}