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
    val username: String = "",      // уникальный id (начинается с @)
    val name: String = "",          // отображаемое имя
    val email: String = "",
    val photoUrl: String? = null,
    val isOnline: Boolean = false,
    val lastActive: Long = 0
)

data class Contact(
    val id: String = "",
    val username: String = "",
    val name: String = "",
    val createdAt: Long? = null
)

private fun DocumentSnapshot.toUserProfile(): UserProfile? {
    val uid = id
    val username = getString("username") ?: ""
    val name = getString("name") ?: getString("username") ?: ""
    val email = getString("email") ?: ""
    val photoUrl = getString("photoUrl")
    val isOnline = getBoolean("isOnline") ?: false
    val lastActive = getLong("lastActive") ?: 0L
    return UserProfile(uid, username, name, email, photoUrl, isOnline, lastActive)
}

private fun DocumentSnapshot.toContact(): Contact? {
    val id = id
    val username = getString("username") ?: ""
    val name = getString("name") ?: username
    val ts = getTimestamp("createdAt")?.toDate()?.time
    return Contact(id, username, name, ts)
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

    suspend fun signUp(usernameInput: String, nameInput: String, email: String, password: String) {
        // 1. Проверяем уникальность username ДО создания пользователя
        val cleanUsername = usernameInput.trim().lowercase().replace(Regex("[^a-z0-9_.]"), "")
        val finalUsername = if (cleanUsername.startsWith("@")) cleanUsername else "@$cleanUsername"
        
        if (!checkUsernameUnique(finalUsername)) {
            throw IllegalArgumentException("Username $finalUsername is already taken")
        }

        auth.createUserWithEmailAndPassword(email, password).await()

        val uid = auth.currentUser!!.uid
        val name = nameInput.ifBlank { "User" }

        val doc = db.collection("users").document(uid)
        val body = mapOf(
            "uid" to uid,
            "username" to finalUsername,
            "username_lc" to finalUsername.lowercase(),
            "name" to name,
            "email" to email,
            "photoUrl" to null,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        doc.set(body).await()
        fcm.registerCurrentToken()
    }

    suspend fun checkUsernameUnique(username: String): Boolean {
        val q = db.collection("users")
            .whereEqualTo("username_lc", username.lowercase())
            .limit(1)
            .get()
            .await()
        return q.isEmpty
    }

    /** Flow текущего профиля пользователя (реальное время) */
    fun currentUserProfileFlow(): Flow<UserProfile?> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val ref = db.collection("users").document(uid)
        val reg = ref.addSnapshotListener { snap, err ->
            if (err != null) {
                // можно отправить null или залогировать
                return@addSnapshotListener
            }
            trySend(snap?.toUserProfile())
        }
        awaitClose { reg.remove() }
    }

    suspend fun uploadProfilePicture(uri: android.net.Uri): String {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("User not logged in")
        val storageRef = com.google.firebase.storage.FirebaseStorage.getInstance().reference
        val photoRef = storageRef.child("users/$uid/profile_image.jpg")

        val metadata = com.google.firebase.storage.StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .build()

        photoRef.putFile(uri, metadata).await()
        val downloadUrl = photoRef.downloadUrl.await().toString()

        // Обновляем профиль
        updateProfile(newPhotoUrl = downloadUrl)
        return downloadUrl
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


    suspend fun updateProfile(
        newUsername: String? = null,
        newName: String? = null,
        newEmail: String? = null,
        newPassword: String? = null,
        newPhotoUrl: String? = null
    ) {
        val user = auth.currentUser ?: throw IllegalStateException("User not logged in")
        val uid = user.uid

        // 0. Check username uniqueness if changing
        if (newUsername != null) {
             val cleanUsername = newUsername.trim().lowercase().replace(Regex("[^a-z0-9_.]"), "")
             val finalUsername = if (cleanUsername.startsWith("@")) cleanUsername else "@$cleanUsername"
             
             // Check if it's actually different from current
             val currentDoc = db.collection("users").document(uid).get().await()
             val currentUsername = currentDoc.getString("username")
             
             if (finalUsername != currentUsername) {
                 if (!checkUsernameUnique(finalUsername)) {
                     throw IllegalArgumentException("Username $finalUsername is already taken")
                 }
             }
        }

        // 1. Update Firebase Auth Profile (Display Name & Photo)
        if (newName != null || newPhotoUrl != null) {
            val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder().apply {
                if (newName != null) setDisplayName(newName)
                if (newPhotoUrl != null) setPhotoUri(android.net.Uri.parse(newPhotoUrl))
            }.build()
            user.updateProfile(profileUpdates).await()
        }

        // 2. Update Email (Auth)
        if (newEmail != null && newEmail != user.email) {
            user.updateEmail(newEmail).await()
        }

        // 3. Update Password (Auth)
        if (newPassword != null) {
            user.updatePassword(newPassword).await()
        }

        // 4. Update Firestore User Document
        val updates = mutableMapOf<String, Any>(
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (newUsername != null) {
             val cleanUsername = newUsername.trim().lowercase().replace(Regex("[^a-z0-9_.]"), "")
             val finalUsername = if (cleanUsername.startsWith("@")) cleanUsername else "@$cleanUsername"
            updates["username"] = finalUsername
            updates["username_lc"] = finalUsername.lowercase()
        }
        if (newName != null) {
            updates["name"] = newName
        }
        if (newEmail != null) {
            updates["email"] = newEmail
        }
        if (newPhotoUrl != null) {
            updates["photoUrl"] = newPhotoUrl
        }

        if (updates.size > 1) { // more than just timestamp
            db.collection("users").document(uid).update(updates).await()
        }
    }

    suspend fun updateOnlineStatus(isOnline: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        val updates = mapOf(
            "isOnline" to isOnline,
            "lastActive" to System.currentTimeMillis()
        )
        try {
            db.collection("users").document(uid).update(updates).await()
        } catch (e: Exception) {
            // Ignore errors for status updates
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
                    "name" to otherProfile.name,
                    "createdAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            b.set(
                theirContacts,
                mapOf(
                    "username" to meProfile.username,
                    "name" to meProfile.name,
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
