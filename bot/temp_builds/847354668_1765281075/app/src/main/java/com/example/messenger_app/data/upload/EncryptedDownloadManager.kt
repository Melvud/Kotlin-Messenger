package com.example.messenger_app.data.upload

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.messenger_app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class EncryptedDownloadManager(
    private val context: Context
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .build()

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progress: Float) : DownloadState()
        data class Success(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    fun downloadMedia(urlComposite: String, mimeType: String, fileName: String? = null): kotlinx.coroutines.flow.Flow<DownloadState> = kotlinx.coroutines.flow.flow {
        emit(DownloadState.Downloading(0f))
        
        // Parse URL|KEY|IV
        val parts = urlComposite.split("|")
        if (parts.size < 3) {
            emit(DownloadState.Error("Invalid encrypted URL format"))
            return@flow
        }
        
        val url = parts[0]
        val keyHex = parts[1]
        val ivHex = parts[2]
        
        android.util.Log.d("EncDownloadMgr", "Starting download: $url, mimeType: $mimeType")
        
        val tempEncFile = File(context.cacheDir, "temp_download_${System.currentTimeMillis()}.enc")
        
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("Failed to download file: $response")
            
            val responseBody = response.body ?: throw IOException("Empty response body")
            val totalBytes = responseBody.contentLength()
            
            responseBody.byteStream().use { input ->
                FileOutputStream(tempEncFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead = 0L
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesRead += bytes
                        if (totalBytes > 0) {
                            emit(DownloadState.Downloading(bytesRead.toFloat() / totalBytes))
                        }
                        bytes = input.read(buffer)
                    }
                }
            }
            android.util.Log.d("EncDownloadMgr", "Download complete. Temp size: ${tempEncFile.length()}")
            
            // Determine output file
            val finalFileName = fileName ?: generateLocalFileName(url, mimeType)
            val outputDir = getOutputDir(mimeType)
            if (!outputDir.exists()) outputDir.mkdirs()
            val outputFile = File(outputDir, finalFileName)
            
            try {
                java.io.FileInputStream(tempEncFile).use { fileIn ->
                    // IV is passed in the string, NOT in the file header anymore
                    val iv = hexStringToByteArray(ivHex)
                    val keyBytes = hexStringToByteArray(keyHex)

                    // Setup Decryption
                    val secretKey = SecretKeySpec(keyBytes, "AES")
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    val spec = GCMParameterSpec(128, iv)
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

                    // Decrypt and Save
                    CipherInputStream(fileIn, cipher).use { cipherIn ->
                        FileOutputStream(outputFile).use { fileOut ->
                            cipherIn.copyTo(fileOut)
                        }
                    }
                }
                android.util.Log.d("EncDownloadMgr", "Decryption complete. Output: ${outputFile.absolutePath}, Size: ${outputFile.length()}")
                emit(DownloadState.Success(outputFile))
                
            } catch (e: Exception) {
                if (outputFile.exists()) outputFile.delete()
                throw IOException("Decryption failed: ${e.message}", e)
            }
            
        } catch (e: Exception) {
            android.util.Log.e("EncDownloadMgr", "Download failed", e)
            emit(DownloadState.Error(e.message ?: "Unknown error"))
        } finally {
            if (tempEncFile.exists()) {
                tempEncFile.delete()
            }
        }
    }.flowOn(Dispatchers.IO)

    fun getLocalFile(urlComposite: String, mimeType: String, fileName: String?): File? {
        // Parse URL from composite string if present
        val url = if (urlComposite.contains("|")) urlComposite.split("|")[0] else urlComposite
        
        val name = fileName ?: "${url.hashCode()}.${getExtension(mimeType)}"
        val file = File(getOutputDir(mimeType), name)
        return if (file.exists()) file else null
    }
    
    // Helper to generate a consistent local filename for a given URL/Message
    fun generateLocalFileName(urlComposite: String, mimeType: String): String {
        val url = if (urlComposite.contains("|")) urlComposite.split("|")[0] else urlComposite
        return "${url.hashCode()}.${getExtension(mimeType)}"
    }

    fun getOutputDir(mimeType: String): File {
        return if (mimeType.startsWith("image")) {
             // Internal cache for images (private) or External for user visibility?
             // User said "Save to App Internal Storage (for images) or Public Gallery (for videos/files)"
             // For simplicity and privacy in "Sovereign Messenger", let's keep images internal for now, 
             // unless user explicitly saves to gallery.
             File(context.filesDir, "images")
        } else if (mimeType.startsWith("video")) {
            File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Messenger")
        } else {
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Messenger")
        }
    }

    private fun getExtension(mimeType: String): String {
        return when {
            mimeType.contains("jpeg", ignoreCase = true) || mimeType.contains("jpg", ignoreCase = true) -> "jpg"
            mimeType.contains("png", ignoreCase = true) -> "png"
            mimeType.contains("gif", ignoreCase = true) -> "gif"
            mimeType.contains("mp4", ignoreCase = true) -> "mp4"
            mimeType.contains("pdf", ignoreCase = true) -> "pdf"
            mimeType.startsWith("video/", ignoreCase = true) -> "mp4" // Default to mp4 for unknown video types
            mimeType.startsWith("image/", ignoreCase = true) -> "jpg" // Default to jpg for unknown image types
            else -> "bin"
        }
    }
}
