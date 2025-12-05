package com.example.messenger_app.data.upload

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class LitterboxRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()

    /**
     * Uploads a file to Litterbox and returns the URL.
     * @param file The file to upload.
     * @return The URL of the uploaded file.
     * @throws IOException If the upload fails.
     */
    fun uploadFile(file: File): String {
        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val fileBody = file.asRequestBody(mediaType)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("reqtype", "fileupload")
            .addFormDataPart("time", "72h")
            .addFormDataPart("fileToUpload", file.name, fileBody)
            .build()

        val request = Request.Builder()
            .url("https://litterbox.catbox.moe/resources/internals/api.php")
            .post(requestBody)
            .build()

        android.util.Log.d("LitterboxRepo", "Uploading file: ${file.name}, size: ${file.length()}")


        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                android.util.Log.e("LitterboxRepo", "Upload failed: $response")
                throw IOException("Unexpected code $response")
            }

            val responseBody = response.body?.string()
            android.util.Log.d("LitterboxRepo", "Upload response: $responseBody")

            if (responseBody.isNullOrBlank() || !responseBody.startsWith("http")) {
                 throw IOException("Invalid response from Litterbox: $responseBody")
            }
            
            return responseBody
        }
    }
}
