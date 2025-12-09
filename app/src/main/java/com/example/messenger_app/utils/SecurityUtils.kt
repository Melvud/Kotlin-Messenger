package com.example.messenger_app.utils

import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {

    // 32 bytes = 256 bits

    private val SECRET_KEY_SPEC: SecretKeySpec by lazy {
        val keyHex = com.example.messenger_app.BuildConfig.APP_SIGNATURE_SALT
        val keyBytes = hexStringToByteArray(keyHex)
        SecretKeySpec(keyBytes, "AES")
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
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12

    fun encrypt(data: String): String {
        return try {
            val iv = ByteArray(IV_LENGTH_BYTE)
            // In a real scenario, use SecureRandom to generate IV
            // For simplicity/determinism in this specific scope, we might use a fixed IV or generate one.
            // Ideally, IV should be random and prepended to the ciphertext.
            // Let's use a simple random IV here.
            java.security.SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SECRET_KEY_SPEC
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val cipherText = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))

            // Combine IV and CipherText
            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            Base64.encodeToString(combined, Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
            data // Fallback: return original data if encryption fails (or handle differently)
        }
    }

    fun decrypt(data: String): String {
        return try {
            if (data.isEmpty()) return ""
            val decoded = Base64.decode(data, Base64.DEFAULT)
            if (decoded.size <= IV_LENGTH_BYTE) return ""

            // Extract IV
            val iv = ByteArray(IV_LENGTH_BYTE)
            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH_BYTE)

            // Extract CipherText
            val cipherTextSize = decoded.size - IV_LENGTH_BYTE
            val cipherText = ByteArray(cipherTextSize)
            System.arraycopy(decoded, IV_LENGTH_BYTE, cipherText, 0, cipherTextSize)

            val cipher = Cipher.getInstance(ALGORITHM)
            val keySpec = SECRET_KEY_SPEC
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            val plainText = cipher.doFinal(cipherText)
            String(plainText, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            "" // Return empty string on failure
        }
    }
    fun generateSharedSecret(otherPublicKeyBase64: String): javax.crypto.SecretKey? {
        return try {
            val keyManager = com.example.messenger_app.utils.KeyManager
            val privateKey = keyManager.getPrivateKey() ?: return null

            val otherPublicKeyBytes = Base64.decode(otherPublicKeyBase64, Base64.DEFAULT)
            val keyFactory = java.security.KeyFactory.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_EC)
            val pubKeySpec = java.security.spec.X509EncodedKeySpec(otherPublicKeyBytes)
            val otherPublicKey = keyFactory.generatePublic(pubKeySpec)

            val keyAgreement = javax.crypto.KeyAgreement.getInstance("ECDH")
            keyAgreement.init(privateKey)
            keyAgreement.doPhase(otherPublicKey, true)

            val sharedSecret = keyAgreement.generateSecret()
            
            // Derive AES key using SHA-256
            val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
            messageDigest.update(sharedSecret)
            val digest = messageDigest.digest()
            
            SecretKeySpec(digest, "AES")
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun encrypt(data: String, secretKey: javax.crypto.SecretKey): String {
        return try {
            val iv = ByteArray(IV_LENGTH_BYTE)
            java.security.SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

            val cipherText = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))

            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            Base64.encodeToString(combined, Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
            data
        }
    }

    fun decrypt(data: String, secretKey: javax.crypto.SecretKey): String {
        return try {
            if (data.isEmpty()) return ""
            val decoded = Base64.decode(data, Base64.DEFAULT)
            if (decoded.size <= IV_LENGTH_BYTE) return ""

            val iv = ByteArray(IV_LENGTH_BYTE)
            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH_BYTE)

            val cipherTextSize = decoded.size - IV_LENGTH_BYTE
            val cipherText = ByteArray(cipherTextSize)
            System.arraycopy(decoded, IV_LENGTH_BYTE, cipherText, 0, cipherTextSize)

            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plainText = cipher.doFinal(cipherText)
            String(plainText, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}
