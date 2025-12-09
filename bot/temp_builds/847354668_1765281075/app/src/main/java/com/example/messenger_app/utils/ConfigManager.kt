package com.example.messenger_app.utils

import android.content.Context
import android.util.Log
import java.io.FileNotFoundException
import java.io.InputStream

class ConfigMissingException(message: String) : Exception(message)

object ConfigManager {
    private const val TAG = "ConfigManager"

    fun getServiceAccountStream(context: Context): InputStream? {
        return try {
            context.assets.open("service-account.json")
        } catch (e: FileNotFoundException) {
            Log.e(TAG, "service-account.json not found in assets")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error opening service-account.json", e)
            null
        }
    }
}
