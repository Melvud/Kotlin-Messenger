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

    private val aesKey: ByteArray by lazy {
        // Ensure the key is 32 bytes for AES-256
        val keyStr = BuildConfig.AES_KEY
        val keyBytes = keyStr.toByteArray(Charsets.UTF_8)
        if (keyBytes.size >= 32) {
            keyBytes.copyOf(32)
        } else {
            // Pad with zeros if too short (Should be handled by env var really)
            val padded = ByteArray(32)
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.size)
            padded
        }
    }

    suspend fun encryptAndUpload(uri: Uri): String = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val tempFile = File(context.cacheDir, "upload_${System.currentTimeMillis()}.enc")

        try {
            android.util.Log.d("EncUploadMgr", "Starting encryption for $uri")
            // 1. Encrypt
            val iv = ByteArray(12) // GCM standard IV size
            SecureRandom().nextBytes(iv)

            val secretKey = SecretKeySpec(aesKey, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    // Write IV first
                    output.write(iv)
                    CipherOutputStream(output, cipher).use { cipherOut ->
                        input.copyTo(cipherOut)
                    }
                }
            } ?: throw IllegalStateException("Could not open input stream for $uri")

            // 2. Upload
            android.util.Log.d("EncUploadMgr", "Encryption done. Temp file size: ${tempFile.length()}. Uploading...")
            val url = litterboxRepository.uploadFile(tempFile)
            android.util.Log.d("EncUploadMgr", "Upload done. URL: $url")
            return@withContext url

        } finally {
            // 3. Cleanup
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}
