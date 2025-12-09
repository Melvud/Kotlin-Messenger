package com.example.messenger_app.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

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
    private val sessionManager: SessionManager,
    private val db: FirebaseFirestore,
    private val msg: FirebaseMessaging,
    private val deviceId: String,
    private val deviceName: String
) {
    suspend fun registerCurrentToken() {
        if (sessionManager.getUid() == null) return
        val token = msg.token.await()
        registerToken(token)
    }

    suspend fun registerToken(token: String) {
        val uid = sessionManager.getUid() ?: return
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
    private val sessionManager: SessionManager,
    private val db: FirebaseFirestore,
    private val fcm: FcmTokenManager
) {
    fun currentUserUid(): String? = sessionManager.getUid()

    private fun hashPassword(password: String): String {
        val bytes = password.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }

    suspend fun signIn(username: String, password: String) {
        val cleanUsername = username.trim().lowercase().replace(Regex("[^a-z0-9_.]"), "")
        val finalUsername = if (cleanUsername.startsWith("@")) cleanUsername else "@$cleanUsername"

        val q = db.collection("users")
            .whereEqualTo("username_lc", finalUsername.lowercase())
            .limit(1)
            .get()
            .await()

        if (q.isEmpty) {
            throw IllegalArgumentException("User not found")
        }

        val doc = q.documents.first()
        val storedHash = doc.getString("passwordHash")
        
        if (storedHash != hashPassword(password)) {
            throw IllegalArgumentException("Invalid password")
        }

        val uid = doc.id
        val name = doc.getString("name") ?: finalUsername
        sessionManager.saveUser(uid, finalUsername, name)
        fcm.registerCurrentToken()
    }

    suspend fun signUp(usernameInput: String, nameInput: String, email: String, password: String) { // Email kept for record but not auth
        // 1. Проверяем уникальность username ДО создания пользователя
        val cleanUsername = usernameInput.trim().lowercase().replace(Regex("[^a-z0-9_.]"), "")
        val finalUsername = if (cleanUsername.startsWith("@")) cleanUsername else "@$cleanUsername"
        
        if (!checkUsernameUnique(finalUsername)) {
            throw IllegalArgumentException("Username $finalUsername is already taken")
        }

        val uid = java.util.UUID.randomUUID().toString()
        val name = nameInput.ifBlank { "User" }
        val passwordHash = hashPassword(password)

        val doc = db.collection("users").document(uid)
        val body = mapOf(
            "uid" to uid,
            "username" to finalUsername,
            "username_lc" to finalUsername.lowercase(),
            "name" to name,
            "email" to email,
            "passwordHash" to passwordHash,
            "photoUrl" to null,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        doc.set(body).await()
        
        sessionManager.saveUser(uid, finalUsername, name)
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
        val uid = sessionManager.getUid()
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
        val uid = sessionManager.getUid() ?: throw IllegalStateException("User not logged in")
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

    suspend fun updateProfile(
        newUsername: String? = null,
        newName: String? = null,
        newEmail: String? = null,
        newPassword: String? = null,
        newPhotoUrl: String? = null
    ) {
        val uid = sessionManager.getUid() ?: throw IllegalStateException("User not logged in")

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

        // 4. Update Firestore User Document
        val updates = mutableMapOf<String, Any>(
            "updatedAt" to FieldValue.serverTimestamp()
        )
        if (newUsername != null) {
             val cleanUsername = newUsername.trim().lowercase().replace(Regex("[^a-z0-9_.]"), "")
             val finalUsername = if (cleanUsername.startsWith("@")) cleanUsername else "@$cleanUsername"
            updates["username"] = finalUsername
            updates["username_lc"] = finalUsername.lowercase()
            // We need current name to save back if not changing
            val currentName = sessionManager.getUserName() ?: ""
            sessionManager.saveUser(uid, finalUsername, currentName) // Update local session
        }
        if (newName != null) {
            updates["name"] = newName
            // Update local session with new name
            val currentUsername = sessionManager.getUsername() ?: ""
            sessionManager.saveUser(uid, currentUsername, newName)
        }
        if (newEmail != null) {
            updates["email"] = newEmail
        }
        if (newPhotoUrl != null) {
            updates["photoUrl"] = newPhotoUrl
        }
        if (newPassword != null) {
            updates["passwordHash"] = hashPassword(newPassword)
        }

        if (updates.size > 1) { // more than just timestamp
            db.collection("users").document(uid).update(updates).await()
        }
    }

    suspend fun updateOnlineStatus(isOnline: Boolean) {
        val uid = sessionManager.getUid() ?: return
        val updates = mapOf(
            "isOnline" to isOnline,
            "lastSeen" to System.currentTimeMillis()
        )
        try {
            db.collection("users").document(uid).update(updates).await()
        } catch (e: Exception) {
            // Ignore errors for status updates
        }
    }
    
    fun signOut() {
        sessionManager.clearUser()
    }
}

// --------- КОНТАКТЫ ---------

class ContactsRepository(
    private val sessionManager: SessionManager,
    private val db: FirebaseFirestore
) {
    private fun requireUid(): String = sessionManager.getUid()
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

        val current = sessionManager.getUid()
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
