package com.example.messenger_app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

// --------- МОДЕЛИ ---------

data class UserProfile(
    val uid: String = "",
    val username: String = "",      // то, что видит пользователь
    val handle: String = "",        // внутренний/бэкенд идентификатор
    val email: String = "",
    val photoUrl: String? = null
)

data class Contact(
    val id: String = "",
    val username: String = "",
    val handle: String = "",
    val createdAt: Long? = null
)

private fun DocumentSnapshot.toUserProfile(): UserProfile? {
    val uid = id
    val username = getString("username")
        ?: getString("name")               // бэк-совместимость
        ?: getString("handle")
        ?: ""
    val handle = getString("handle") ?: ""
    val email = getString("email") ?: ""
    val photoUrl = getString("photoUrl")
    return UserProfile(uid, username, handle, email, photoUrl)
}

private fun DocumentSnapshot.toContact(): Contact? {
    val id = id
    val username = getString("username")
        ?: getString("name")
        ?: ""
    val handle = getString("handle") ?: ""
    val ts = getTimestamp("createdAt")?.toDate()?.time
    return Contact(id, username, handle, ts)
}

// --------- FCM ТОКЕНЫ ---------

class FcmTokenManager(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val msg: FirebaseMessaging,
    private val deviceId: String,
    private val deviceName: String
) {
    suspend fun registerCurrentToken() {
        if (auth.currentUser == null) return
        val token = msg.token.await()
        registerToken(token)
    }

    suspend fun registerToken(token: String) {
        val uid = auth.currentUser?.uid ?: return
        // поддержка мульти-девайсов: users/{uid}/devices/{deviceId}
        val ref = db.collection("users").document(uid)
            .collection("devices").document(deviceId)

        val payload = mapOf(
            "token" to token,
            "deviceName" to deviceName,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        ref.set(payload, com.google.firebase.firestore.SetOptions.merge()).await()
    }
}

// --------- ПОЛЬЗОВАТЕЛИ ---------

class UserRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val fcm: FcmTokenManager
) {
    fun currentUser(): FirebaseUser? = auth.currentUser

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
        ensureUserDoc()
        fcm.registerCurrentToken()
    }

    suspend fun signUp(usernameInput: String, email: String, password: String) {
        auth.createUserWithEmailAndPassword(email, password).await()

        val uid = auth.currentUser!!.uid
        val username = usernameInput.ifBlank {
            email.substringBefore("@")
        }.lowercase().replace(Regex("[^a-z0-9_\\-.]"), "")

        // handle — чисто бэкенд, можно оставить такой же как username или иным способом
        val handle = username

        val doc = db.collection("users").document(uid)
        val body = mapOf(
            "uid" to uid,
            // UI-поля
            "username" to username,
            "username_lc" to username.lowercase(),
            // для совместимости: name дублируем username
            "name" to username,
            // бэкенд-поля
            "handle" to handle,
            "handle_lc" to handle.lowercase(),
            "email" to email,
            "photoUrl" to null,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        doc.set(body).await()
        fcm.registerCurrentToken()
    }

    private suspend fun ensureUserDoc() {
        val uid = auth.currentUser?.uid ?: return
        val docRef = db.collection("users").document(uid)
        val snap = docRef.get().await()
        if (!snap.exists()) {
            val email = auth.currentUser?.email ?: ""
            val base = (auth.currentUser?.displayName ?: email.substringBefore("@"))
                .lowercase().replace(Regex("[^a-z0-9_\\-.]"), "")
            val payload = mapOf(
                "uid" to uid,
                "username" to base,
                "username_lc" to base,
                "name" to base, // для старого UI
                "handle" to base,
                "handle_lc" to base,
                "email" to email,
                "photoUrl" to auth.currentUser?.photoUrl?.toString(),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
        } else {
            // one-time миграция: если нет username/username_lc — проставим
            val data = snap.data ?: return
            val hasUsername = data["username"] != null
            if (!hasUsername) {
                val base = (data["name"] as? String)
                    ?: (data["handle"] as? String)
                    ?: (auth.currentUser?.email?.substringBefore("@") ?: "user")
                val norm = base.lowercase().replace(Regex("[^a-z0-9_\\-.]"), "")
                docRef.update(
                    mapOf(
                        "username" to norm,
                        "username_lc" to norm
                    )
                ).await()
            }
        }
    }
}

// --------- КОНТАКТЫ ---------

class ContactsRepository(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore
) {
    private fun requireUid(): String = auth.currentUser?.uid
        ?: error("User not authenticated")

    /** Живой список контактов текущего пользователя (сортировка по username) */
    fun contactsFlow(): Flow<List<Contact>> = callbackFlow {
        val uid = requireUid()
        val ref = db.collection("users").document(uid)
            .collection("contacts")
            .orderBy("username", Query.Direction.ASCENDING)

        val reg = ref.addSnapshotListener { snap, err ->
            if (err != null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { it.toContact() }.orEmpty()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    /** Поиск по префиксу username (видимый пользователю). */
    fun searchUsersByUsernameFlow(prefix: String): Flow<List<UserProfile>> = callbackFlow {
        val q = prefix.trim().lowercase()
        if (q.isEmpty()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val ref = db.collection("users")
            .orderBy("username_lc")
            .startAt(q)
            .endAt("$q\uf8ff")
            .limit(20)

        val current = auth.currentUser?.uid
        val reg = ref.addSnapshotListener { snap, err ->
            if (err != null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snap?.documents
                ?.mapNotNull { it.toUserProfile() }
                ?.filter { it.uid != current } // не показываем себя
                .orEmpty()
            trySend(list)
        }
        awaitClose { reg.remove() }
    }

    /** Добавить контакт ОБОИМ пользователям (в сабколлекции contacts). */
    suspend fun addContactMutualByUid(otherUid: String) {
        val me = requireUid()
        if (otherUid == me) error("Нельзя добавить себя")

        val meDoc = db.collection("users").document(me).get().await()
        val otherDoc = db.collection("users").document(otherUid).get().await()
        val meProfile = meDoc.toUserProfile() ?: error("Профиль не найден")
        val otherProfile = otherDoc.toUserProfile() ?: error("Контакт не найден")

        db.runBatch { b ->
            val myContacts = db.collection("users").document(me)
                .collection("contacts").document(otherUid)
            val theirContacts = db.collection("users").document(otherUid)
                .collection("contacts").document(me)

            b.set(
                myContacts,
                mapOf(
                    "username" to otherProfile.username,
                    "handle" to otherProfile.handle, // остаётся на бэкенд
                    "createdAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            b.set(
                theirContacts,
                mapOf(
                    "username" to meProfile.username,
                    "handle" to meProfile.handle,
                    "createdAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
        }.await()
    }

    /** Удобно: найти по username и добавить. */
    suspend fun addContactMutualByUsername(username: String) {
        val u = username.trim().lowercase()
        val q = db.collection("users")
            .whereEqualTo("username_lc", u)
            .limit(1)
            .get()
            .await()

        val user = q.documents.firstOrNull()?.toUserProfile()
            ?: error("Пользователь @$u не найден")

        addContactMutualByUid(user.uid)
    }
}
