package com.example.messenger_app.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class CallInfo(
    val id: String,
    val callerUid: String,
    val calleeUid: String,
    val callType: String // "audio" | "video"
)

class CallsRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    // ✅ ИЗМЕНЕНИЕ: Добавлены параметры по умолчанию для scope и functions
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(),
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
                val data = mapOf(
                    "toUserId" to calleeUid,
                    "fromUserId" to me,
                    "fromUsername" to fromUsername,
                    "callId" to callId,
                    "callType" to callType
                )

                Log.d("CallsRepository", "Sending call notification: caller=$me, callee=$calleeUid, type=$callType")

                runCatching {
                    functions.getHttpsCallable("sendCallNotification")
                        .call(data)
                        .await()
                }.onFailure { e ->
                    Log.w("CallsRepository", "sendCallNotification failed: ${e.message}", e)
                }
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
    suspend fun hangupOtherDevices(callId: String) {
        val acceptedToken = FirebaseMessaging.getInstance().token.await()
        val data = mapOf(
            "callId" to callId,
            "acceptedToken" to acceptedToken
        )
        functions.getHttpsCallable("hangupOtherDevices")
            .call(data)
            .await()
    }
}