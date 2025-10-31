package com.example.messenger_app.push

import android.content.Context
import androidx.core.content.edit

object OngoingCallStore {
    data class State(
        val callId: String,
        val callType: String, // "audio" | "video"
        val username: String
    )

    private const val PREF = "ongoing_call_prefs"
    private const val K_ID = "id"
    private const val K_TYPE = "type"
    private const val K_USER = "user"

    fun save(context: Context, callId: String, callType: String, username: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit {
            putString(K_ID, callId)
            putString(K_TYPE, callType)
            putString(K_USER, username)
        }
    }

    fun load(context: Context): State? {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = sp.getString(K_ID, null) ?: return null
        val type = sp.getString(K_TYPE, null) ?: return null
        val user = sp.getString(K_USER, "") ?: ""
        return State(id, type, user)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit { clear() }
    }
}
