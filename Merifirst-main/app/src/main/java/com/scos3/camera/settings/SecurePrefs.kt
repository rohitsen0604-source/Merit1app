package com.scos3.camera.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Minimal encrypted key/value store for secrets (Gmail credentials).
 *
 * The encryption key never leaves the device: it is generated inside the
 * Android Keystore (`AndroidKeyStore` provider) and only ever used for
 * decrypt operations in this process. Ciphertext (IV + AES/GCM) is stored in
 * SharedPreferences; the plaintext credentials never touch:
 *  - source code or BuildConfig,
 *  - plain SharedPreferences,
 *  - logs or notifications.
 *
 * GCM authentication also guarantees the ciphertext has not been tampered
 * with. No external dependency is required.
 */
class SecurePrefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Throws(Exception::class)
    fun putString(key: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = Base64.encodeToString(iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(cipherText, Base64.NO_WRAP)
        prefs.edit().putString(key, blob).apply()
    }

    fun getString(key: String): String? {
        val blob = prefs.getString(key, null) ?: return null
        return runCatching {
            val parts = blob.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "scos3_secure"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "scos3_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
