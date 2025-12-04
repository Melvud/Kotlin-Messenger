package com.example.messenger_app.data.push

import android.content.Context
import android.util.Log
import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class PushRepository(private val context: Context) {

    companion object {
        private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        private const val FCM_API_URL_TEMPLATE = "https://fcm.googleapis.com/v1/projects/%s/messages:send"
    }

    suspend fun sendDirectPush(
        targetToken: String,
        title: String? = null,
        body: String? = null,
        data: Map<String, String>
    ) {
        withContext(Dispatchers.IO) {
            try {
                // 1. Load service-account.json
                val inputStream = com.example.messenger_app.utils.ConfigManager.getServiceAccountStream(context)
                    ?: throw com.example.messenger_app.utils.ConfigMissingException("Ключ конфигурации не найден")

                // Parse project_id from JSON
                val jsonString = inputStream.bufferedReader().use { it.readText() }
                
                // Reset stream for GoogleCredentials (re-open)
                val inputStreamForCreds = com.example.messenger_app.utils.ConfigManager.getServiceAccountStream(context)
                    ?: throw com.example.messenger_app.utils.ConfigMissingException("Ключ конфигурации не найден")
                
                val jsonObject = JSONObject(jsonString)
                val projectId = jsonObject.getString("project_id")

                // 2. Get Access Token
                val googleCredentials = GoogleCredentials.fromStream(inputStreamForCreds)
                    .createScoped(listOf(SCOPE))
                googleCredentials.refreshIfExpired()
                val accessToken = googleCredentials.accessToken.tokenValue

                // 3. Build JSON Payload
                val messageJson = JSONObject()
                val messageContent = JSONObject()

                messageContent.put("token", targetToken)

                if (title != null || body != null) {
                    val notification = JSONObject()
                    if (title != null) notification.put("title", title)
                    if (body != null) notification.put("body", body)
                    messageContent.put("notification", notification)
                }

                val dataJson = JSONObject()
                data.forEach { (key, value) ->
                    dataJson.put(key, value)
                }
                messageContent.put("data", dataJson)

                val androidConfig = JSONObject()
                androidConfig.put("priority", "high")
                messageContent.put("android", androidConfig)

                messageJson.put("message", messageContent)

                // 4. Send POST Request
                val url = URL(String.format(FCM_API_URL_TEMPLATE, projectId))
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
                connection.setRequestProperty("Content-Type", "application/json; UTF-8")
                connection.doOutput = true

                val outputStream = connection.outputStream
                outputStream.write(messageJson.toString().toByteArray(StandardCharsets.UTF_8))
                outputStream.flush()
                outputStream.close()

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    Log.d("PushRepository", "Push sent successfully")
                } else {
                    val errorStream = connection.errorStream
                    val errorResponse = errorStream?.bufferedReader()?.use { it.readText() }
                    Log.e("PushRepository", "Failed to send push: $responseCode, $errorResponse")
                }
                connection.disconnect()

            } catch (e: Exception) {
                Log.e("PushRepository", "Error sending push", e)
            }
        }
    }
}
