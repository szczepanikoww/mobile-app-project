package com.trainingapp.server.platform

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for private columns. Output is a random 12-byte nonce followed by ciphertext and tag. [keyId] is stored
 * next to the data so the key can be rotated later.
 */
class DataCipher(val keyId: String, key: ByteArray) {
    private val keySpec: SecretKeySpec

    init {
        require(key.size == 32) { "Data key must be 32 bytes" }
        keySpec = SecretKeySpec(key, "AES")
    }

    private val random = SecureRandom()

    fun encrypt(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        return nonce + cipher.doFinal(plain)
    }

    /** Throws when the data was tampered with or the key is wrong. */
    fun decrypt(data: ByteArray): ByteArray {
        require(data.size > NONCE_BYTES) { "Ciphertext too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, data.copyOfRange(0, NONCE_BYTES)))
        return cipher.doFinal(data, NONCE_BYTES, data.size - NONCE_BYTES)
    }

    private companion object {
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
