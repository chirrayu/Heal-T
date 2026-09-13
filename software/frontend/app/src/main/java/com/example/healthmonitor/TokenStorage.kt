package com.example.healthmonitor

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure Token Storage backed by Android Keystore System and EncryptedSharedPreferences.
 *
 * Prevents plain-text JWT token exposure on rooted devices, ADB backups, or flash dumps.
 * Uses AES256_SIV for key encryption and AES256_GCM for value encryption.
 */
object TokenStorage {

    private const val TAG = "TokenStorage"
    private const val PREFS_FILENAME = "secure_healthmonitor_prefs"
    private const val KEY_AUTH_TOKEN = "jwt_auth_token"
    private const val KEY_USER_ROLE = "user_role"
    private const val KEY_OPERATOR_NAME = "operator_name"

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return encryptedPrefs ?: synchronized(this) {
            encryptedPrefs ?: try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILENAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { encryptedPrefs = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize EncryptedSharedPreferences, resetting corrupted keystore prefs", e)
                // If keys become corrupted (e.g. app reinstall without clearing data or Keystore reset), recreate
                context.applicationContext.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE).edit().clear().apply()
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILENAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { encryptedPrefs = it }
            }
        }
    }

    fun saveToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_AUTH_TOKEN, token).apply()
    }

    fun getToken(context: Context): String? {
        return getPrefs(context).getString(KEY_AUTH_TOKEN, null)
    }

    fun saveUserRole(context: Context, role: String) {
        getPrefs(context).edit().putString(KEY_USER_ROLE, role).apply()
    }

    fun getUserRole(context: Context): String? {
        return getPrefs(context).getString(KEY_USER_ROLE, null)
    }

    fun saveOperatorName(context: Context, name: String) {
        getPrefs(context).edit().putString(KEY_OPERATOR_NAME, name).apply()
    }

    fun getOperatorName(context: Context): String? {
        return getPrefs(context).getString(KEY_OPERATOR_NAME, null)
    }

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun hasToken(context: Context): Boolean {
        val token = getToken(context)
        return !token.isNullOrBlank()
    }
}
