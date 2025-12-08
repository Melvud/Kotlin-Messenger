package com.example.messenger_app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_session", Context.MODE_PRIVATE)
    
    private val _currentUserUid = MutableStateFlow<String?>(prefs.getString("uid", null))
    val currentUserUid: StateFlow<String?> = _currentUserUid.asStateFlow()

    fun saveUser(uid: String, username: String, name: String) {
        prefs.edit()
            .putString("uid", uid)
            .putString("username", username)
            .putString("name", name)
            .apply()
        _currentUserUid.value = uid
    }

    fun clearUser() {
        prefs.edit().clear().apply()
        _currentUserUid.value = null
    }

    fun getUid(): String? = prefs.getString("uid", null)
    fun getUsername(): String? = prefs.getString("username", null)
    fun getUserName(): String? = prefs.getString("name", null)
}
