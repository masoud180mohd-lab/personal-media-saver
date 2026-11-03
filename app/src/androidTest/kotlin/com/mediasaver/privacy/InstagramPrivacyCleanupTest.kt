package com.mediasaver.privacy

import android.webkit.CookieManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InstagramPrivacyCleanupTest {

    @Test
    fun cleanupDeletesLegacyPreferencesAndWebViewCookiesAndIsRepeatable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val legacyFile = File(context.applicationInfo.dataDir, "shared_prefs/ig_session_prefs.xml")
        // The encrypted and plaintext versions used the same file. Delete the whole store,
        // including opaque encrypted keys, without interpreting any credential values.
        assertTrue(
            context.getSharedPreferences("ig_session_prefs", 0).edit()
                .putString("ig_cookies", "sessionid=legacy-test-session")
                .putString("opaque_encrypted_key", "opaque_encrypted_value")
                .putBoolean("ig_logged_in", true)
                .commit()
        )
        assertTrue(legacyFile.exists())
        val legacyBackup = File(legacyFile.path + ".bak")
        legacyFile.copyTo(legacyBackup, overwrite = true)

        val cookieSet = CountDownLatch(1)
        instrumentation.runOnMainSync {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setCookie("https://www.instagram.com", "sessionid=legacy-test-session; Secure") {
                    flush()
                    cookieSet.countDown()
                }
            }
        }
        assertTrue("Cookie setup did not complete", cookieSet.await(10, TimeUnit.SECONDS))
        assertTrue(
            CookieManager.getInstance().getCookie("https://www.instagram.com")
                .orEmpty().contains("sessionid=legacy-test-session")
        )

        repeat(2) {
            val cookiesCleared = CountDownLatch(1)
            instrumentation.runOnMainSync {
                InstagramPrivacyCleanup.clearLegacyData(context) { cookiesCleared.countDown() }
            }
            assertTrue("Cookie cleanup did not complete", cookiesCleared.await(10, TimeUnit.SECONDS))
            assertFalse(legacyFile.exists())
            assertFalse(legacyBackup.exists())
            assertTrue(CookieManager.getInstance().getCookie("https://www.instagram.com").isNullOrBlank())
            assertFalse(CookieManager.getInstance().acceptCookie())
        }
    }
}
