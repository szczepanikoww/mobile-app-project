package com.trainingapp.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.trainingapp.data.auth.StoredAuth
import com.trainingapp.data.auth.StoredAuthCodec
import com.trainingapp.data.auth.TokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Firebase session, encrypted with AES-256-GCM under a key that never leaves the Android Keystore
 * (docs/plans/firebase-auth.md §6.3). Only the ciphertext is in private SharedPreferences; backups are off for the app
 * (`allowBackup="false"`). Anything unreadable (key lost after a device restore, a corrupt value) is deleted and the
 * user signs in again. Nothing is logged.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): StoredAuth? {
        val stored = prefs.getString(KEY_VALUE, null) ?: return null
        val decoded = runCatching { StoredAuthCodec.decode(decrypt(stored)) }.getOrNull()
        if (decoded == null) clear()
        return decoded
    }

    override fun write(auth: StoredAuth) {
        val encrypted = runCatching { encrypt(StoredAuthCodec.encode(auth)) }.getOrNull()
        // Without a working key the session is simply not kept across restarts.
        if (encrypted == null) clear() else prefs.edit().putString(KEY_VALUE, encrypted).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_VALUE).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "auth_session"
        const val KEY_VALUE = "session_v1"
        const val KEY_ALIAS = "trainer_auth_session"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
