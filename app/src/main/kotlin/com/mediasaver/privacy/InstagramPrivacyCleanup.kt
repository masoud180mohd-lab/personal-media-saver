package com.mediasaver.privacy

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.mediasaver.core.common.AppLogger

/**
 * Removes credentials left by versions that offered Instagram sign-in.
 * Called on the main thread at every startup, so failed cleanup is retried next launch.
 * No credentials are read or restored.
 */
object InstagramPrivacyCleanup {
    private const val TAG = "MediaSaver:InstagramPrivacy"
    private const val LEGACY_PREFERENCES = "ig_session_prefs"

    fun clearLegacyData(context: Context, onCookiesCleared: () -> Unit = {}) {
        attempt("preferences") {
            check(context.deleteSharedPreferences(LEGACY_PREFERENCES)) {
                "Legacy preferences could not be deleted"
            }
        }
        attempt("cookies") {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(false)
            cookies.removeAllCookies {
                attempt("cookie flush") { cookies.flush() }
                onCookiesCleared()
            }
        }
        attempt("web storage") {
            WebStorage.getInstance().deleteAllData()
        }
        attempt("form data") {
            WebViewDatabase.getInstance(context).apply {
                clearFormData()
                clearHttpAuthUsernamePassword()
            }
        }
        attempt("web cache") {
            val webView = WebView(context)
            try {
                webView.clearCache(true)
                webView.clearHistory()
                webView.clearFormData()
            } finally {
                webView.destroy()
            }
        }
    }

    private inline fun attempt(step: String, action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            // Never log cookies, credential values, or WebView URLs.
            AppLogger.e(TAG, "Legacy Instagram $step cleanup failed; will retry next launch.")
        }
    }
}
