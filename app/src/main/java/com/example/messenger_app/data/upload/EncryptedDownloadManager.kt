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

    private val aesKey: ByteArray by lazy {
        val keyStr = BuildConfig.AES_KEY
        val keyBytes = keyStr.toByteArray(Charsets.UTF_8)
        if (keyBytes.size >= 32) {
            keyBytes.copyOf(32)
        } else {
            val padded = ByteArray(32)
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.size)
            padded
        }
    }

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progress: Float) : DownloadState()
        data class Success(val file: File) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    fun downloadMedia(url: String, mimeType: String, fileName: String? = null): kotlinx.coroutines.flow.Flow<DownloadState> = kotlinx.coroutines.flow.flow {
        emit(DownloadState.Downloading(0f))
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
                    // Read IV (first 12 bytes)
                    val iv = ByteArray(12)
                    var totalRead = 0
                    while (totalRead < 12) {
                        val read = fileIn.read(iv, totalRead, 12 - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    if (totalRead != 12) throw IOException("Invalid file format: missing or incomplete IV")

                    // Setup Decryption
                    val secretKey = SecretKeySpec(aesKey, "AES")
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

    fun getLocalFile(url: String, mimeType: String, fileName: String?): File? {
        // This is a heuristic check. Since we don't have a database of downloaded files mapping URL -> Path,
        // we can try to guess based on filename if provided, or we might need to rely on the caller tracking this.
        // For now, let's assume the caller might pass the expected filename.
        // If not, we can't easily check if *this specific URL* was downloaded without a DB.
        // BUT, for the chat, we can check if the file exists in our cache directory with a hash of the URL?
        // Let's implement a simple hash-based filename strategy for caching if no filename is provided.
        
        val name = fileName ?: "${url.hashCode()}.${getExtension(mimeType)}"
        val file = File(getOutputDir(mimeType), name)
        return if (file.exists()) file else null
    }
    
    // Helper to generate a consistent local filename for a given URL/Message
    fun generateLocalFileName(url: String, mimeType: String): String {
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
