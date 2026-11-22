package com.mediasaver

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [33])
class LegacyInstagramCleanupTest {
    @Test
    fun `upgrade purges opaque and plaintext records and retries unavailable WebView`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("ig_session_prefs", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("ig_cookies", "fixture")
            .putString("opaque-key", "opaque-value").commit())
        val xml = File(context.applicationInfo.dataDir, "shared_prefs/ig_session_prefs.xml")
        assertTrue(xml.exists())
        val backup = File(xml.path + ".bak")
        xml.copyTo(backup, overwrite = true)
        var attempts = 0
        LegacyInstagramCleanup.run(context) { attempts++; error("WebView unavailable") }
        assertFalse(xml.exists())
        assertFalse(backup.exists())
        assertTrue(context.getSharedPreferences("ig_session_prefs", Context.MODE_PRIVATE).all.isEmpty())
        repeat(2) { LegacyInstagramCleanup.run(context) { attempts++ } }
        assertEquals(2, attempts)
    }
}
