package com.example.messenger_app.data.upload

import android.content.Context
import android.net.Uri
import com.example.messenger_app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedUploadManager(
    private val context: Context,
    private val litterboxRepository: LitterboxRepository
) {

    // Helper to convert bytes to Hex
    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun encryptAndUpload(uri: Uri): String = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}.enc")

        try {
            android.util.Log.d("EncUploadMgr", "Starting encryption for $uri")
            
            // 1. Generate Random Key and IV
            val keyBytes = ByteArray(32) // 256-bit key
            SecureRandom().nextBytes(keyBytes)
            
            val iv = ByteArray(12) // GCM standard IV size
            SecureRandom().nextBytes(iv)

            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    // Do NOT write IV to file, it will be passed in the string
                    CipherOutputStream(output, cipher).use { cipherOut ->
                        input.copyTo(cipherOut)
                    }
                }
            } ?: throw IllegalStateException("Could not open input stream for $uri")

            // 2. Upload
            android.util.Log.d("EncUploadMgr", "Encryption done. Temp file size: ${tempFile.length()}. Uploading...")
            val url = litterboxRepository.uploadFile(tempFile)
            android.util.Log.d("EncUploadMgr", "Upload done. URL: $url")
            
            // 3. Return URL|KEY|IV
            val keyHex = toHex(keyBytes)
            val ivHex = toHex(iv)
            return@withContext "$url|$keyHex|$ivHex"

        } finally {
            // 4. Cleanup
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
