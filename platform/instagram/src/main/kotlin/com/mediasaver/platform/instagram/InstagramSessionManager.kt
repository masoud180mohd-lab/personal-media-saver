package com.mediasaver.platform.instagram

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Instagram session cookies locally on-device.
 * Cookies are stored in EncryptedSharedPreferences and never transmitted externally.
 * Adheres to zero-telemetry contract.
 */
@Singleton
class InstagramSessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "InstagramSessionManager"
        private const val PREF_FILE = "ig_session_prefs"
        private const val KEY_COOKIES = "ig_cookies"
        private const val KEY_LOGGED_IN = "ig_logged_in"
    }

    private val prefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to normal prefs", e)
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * Saves the full cookie header string from a successful Instagram session.
     */
    fun saveCookies(cookieString: String) {
        prefs.edit()
            .putString(KEY_COOKIES, cookieString)
            .putBoolean(KEY_LOGGED_IN, true)
            .apply()
        Log.d(TAG, "Instagram cookies saved (${cookieString.length} chars)")
    }

    /**
     * Returns the stored cookie header string, or null if not logged in.
     */
    fun getCookies(): String? {
        return prefs.getString(KEY_COOKIES, null)
    }

    /**
     * Whether the user has an active Instagram session.
     */
    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_LOGGED_IN, false) && !getCookies().isNullOrBlank()
    }

    /**
     * Clears saved session data (logout).
     */
    fun clearSession() {
        prefs.edit()
            .remove(KEY_COOKIES)
            .putBoolean(KEY_LOGGED_IN, false)
            .apply()
        Log.d(TAG, "Instagram session cleared")
    }
}
