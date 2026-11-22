package com.mediasaver

import android.app.Application
import android.net.Uri
import com.mediasaver.core.database.di.DatabaseModule
import com.mediasaver.core.domain.repository.SettingsRepository
import com.mediasaver.core.model.*
import com.mediasaver.core.storage.MediaStoreManager
import com.mediasaver.platform.whatsapp.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [33])
class WhatsAppStatusSaveTest {
    @Test fun `equal SAF bytes reuse one gallery entry and mark both status rows saved`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = DatabaseModule.provideMediaSaverDatabase(context)
        val dao = DatabaseModule.provideWhatsAppStatusDao(db)
        val mapper = WhatsAppStatusMapper()
        val repo = StatusRepositoryImpl(context, dao, StatusFileScanner(context), mapper, MediaStoreManager(context), settings)
        val bytes = byteArrayOf(-1, -40, -1, 0)
        val paths = (1..2).map { id ->
            val uri = Uri.parse("content://status/source/$id")
            shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
            val status = WhatsAppStatus("$id", "photo.jpg", uri.toString(), StatusMediaType.IMAGE, bytes.size.toLong(), 1L)
            dao.insert(mapper.toEntity(status))
            repo.saveStatus(status, false).getOrThrow()
        }
        assertEquals(paths.first(), paths.last())
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        for (id in 1..2) {
            val saved = requireNotNull(dao.getByOriginalUri("content://status/source/$id"))
            assertEquals(expectedHash, saved.fileHash)
            assertEquals(paths.first(), saved.savedPath)
            assertTrue(saved.isSaved)
        }
    }

    private val settings = object : SettingsRepository {
        override val settings = flowOf(AppSettings())
        override suspend fun getSettings() = AppSettings()
        override suspend fun updatePreferredQuality(quality: String) {}
        override suspend fun updateDownloadDirectoryUri(uri: String?) {}
        override suspend fun updateMaxConcurrentDownloads(limit: Int) {}
        override suspend fun updateWifiOnlyDownload(wifiOnly: Boolean) {}
        override suspend fun updateMaxDownloadSizeMb(maxSizeMb: Int) {}
        override suspend fun updateWhatsAppStatusUri(uri: String?) {}
        override suspend fun updateWhatsAppBusinessStatusUri(uri: String?) {}
        override suspend fun updateSaveDownloadHistory(enabled: Boolean) {}
        override suspend fun updateAutoDeleteTempFiles(enabled: Boolean) {}
        override suspend fun updateThemeMode(themeMode: ThemeMode) {}
        override suspend fun resetToDefaults() {}
    }

}
