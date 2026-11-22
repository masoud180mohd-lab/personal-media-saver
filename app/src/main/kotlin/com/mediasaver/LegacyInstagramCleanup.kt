package com.mediasaver

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

internal object LegacyInstagramCleanup {
    private const val PREFS = "ig_session_prefs"

    // Called from Dispatchers.IO. Record success outside backup so restored data is cleaned too.
    suspend fun run(context: Context, clearWebData: suspend () -> Unit = ::clearWebViewData) {
        try {
            val marker = File(context.noBackupFilesDir, "instagram_cleanup_v1")
            if (marker.exists()) return
            val directory = File(context.applicationInfo.dataDir, "shared_prefs")
            val files = listOf(File(directory, "$PREFS.xml"), File(directory, "$PREFS.xml.bak"))
            try {
                context.deleteSharedPreferences(PREFS) // minSdk 26
            } finally {
                // Also remove any remaining plaintext/encrypted XML or interrupted-write backup.
                for (file in files) {
                    if (file.exists() && !file.delete()) throw IOException("Legacy preferences still exist")
                }
            }
            // A missing WebView provider can also leave a callback unanswered.
            if (withTimeoutOrNull(15_000L) { clearWebData(); true } != true) return
            marker.createNewFile()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Storage or the system WebView may be unavailable. Retry at the next launch.
        } catch (_: LinkageError) {
            // Some headless systems have no loadable WebView provider.
        }
    }

    private suspend fun clearWebViewData() {
        val cookies = withContext(Dispatchers.Main.immediate) {
            val manager = CookieManager.getInstance()
            suspendCancellableCoroutine<Unit> { continuation ->
                manager.removeAllCookies { continuation.resume(Unit) }
            }
            manager
        }
        cookies.flush()
        if (cookies.hasCookies()) throw IOException("Cookie cleanup is incomplete")
        withContext(Dispatchers.Main.immediate) {
            val storage = WebStorage.getInstance()
            storage.deleteAllData()
            val empty = suspendCancellableCoroutine<Boolean> { continuation ->
                storage.getOrigins { origins -> continuation.resume(origins.isNullOrEmpty()) }
            }
            if (!empty) throw IOException("Web storage cleanup is incomplete")
        }
    }
}
