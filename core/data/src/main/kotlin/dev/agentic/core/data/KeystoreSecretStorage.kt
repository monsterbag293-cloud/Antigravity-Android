package dev.agentic.core.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android Keystore-backed Secret Storage (ADR-02).
 * API keys and device credentials are encrypted using hardware master keys.
 * Zero credentials are baked into the APK.
 */
class KeystoreSecretStorage(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "agentic_secure_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(providerId: String, apiKey: String) {
        sharedPreferences.edit().putString("key_$providerId", apiKey).apply()
    }

    fun getApiKey(providerId: String): String? {
        return sharedPreferences.getString("key_$providerId", null)
    }

    fun hasApiKey(providerId: String): Boolean {
        return !getApiKey(providerId).isNullOrBlank()
    }

    fun clearApiKey(providerId: String) {
        sharedPreferences.edit().remove("key_$providerId").apply()
    }
}
