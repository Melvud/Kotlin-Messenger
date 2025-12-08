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
        val keyHex = BuildConfig.AES_KEY
        hexStringToByteArray(keyHex)
    }

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
