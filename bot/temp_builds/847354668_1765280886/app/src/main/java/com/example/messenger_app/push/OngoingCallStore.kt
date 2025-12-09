package com.example.messenger_app.push

import android.content.Context
import androidx.core.content.edit

object OngoingCallStore {
    data class State(
        val callId: String,
        val isVideo: Boolean,
        val username: String
    )

    private const val PREF = "ongoing_call_prefs"
    private const val K_ID = "id"
    private const val K_IS_VIDEO = "is_video"
    private const val K_USER = "user"

    fun save(context: Context, callId: String, isVideo: Boolean, username: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit {
            putString(K_ID, callId)
            putBoolean(K_IS_VIDEO, isVideo)
            putString(K_USER, username)
        }
    }

    fun load(context: Context): State? {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val id = sp.getString(K_ID, null) ?: return null
        val isVideo = sp.getBoolean(K_IS_VIDEO, false)
        val user = sp.getString(K_USER, "") ?: ""
        return State(id, isVideo, user)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit { clear() }
    }
}
