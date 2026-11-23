package com.mediasaver

import android.app.Application
import android.net.Uri
import android.os.Looper
import com.mediasaver.core.database.di.DatabaseModule
import com.mediasaver.core.database.entity.WhatsAppStatusEntity
import com.mediasaver.core.domain.repository.SettingsRepository
import com.mediasaver.core.domain.repository.StatusRepository
import com.mediasaver.core.domain.usecase.*
import com.mediasaver.core.model.*
import com.mediasaver.core.storage.MediaStoreManager
import com.mediasaver.feature.status.StatusViewModel
import com.mediasaver.platform.whatsapp.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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

    @Test fun `observeAll orders newest by lastModified and deleteUnsavedNotIn keeps saved statuses`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = DatabaseModule.provideMediaSaverDatabase(context)
        val dao = DatabaseModule.provideWhatsAppStatusDao(db)
        dao.deleteAll()

        dao.insert(WhatsAppStatusEntity("newest", "3.jpg", "content://status/3", "IMAGE", 10L, 3000L, "", isSaved = false, discoveredAt = 100L))
        dao.insert(WhatsAppStatusEntity("saved", "2.jpg", "content://status/2", "IMAGE", 10L, 2000L, "hash2", isSaved = true, savedPath = "content://media/2", discoveredAt = 200L))
        dao.insert(WhatsAppStatusEntity("oldest", "1.jpg", "content://status/1", "IMAGE", 10L, 1000L, "", isSaved = false, discoveredAt = 300L))

        assertEquals(listOf("newest", "saved", "oldest"), dao.observeAll().first().map { it.id })

        dao.deleteUnsavedNotIn(listOf("content://status/3"))
        assertEquals(listOf("newest", "saved"), dao.observeAll().first().map { it.id })
    }

    @Test fun `StatusViewModel saveStatuses saves unsaved items sequentially with a single summary message`() = runBlocking {
        val savedIds = mutableListOf<String>()
        val failingIds = mutableSetOf<String>()
        val repo = object : StatusRepository {
            override fun observeStatuses() = flowOf(emptyList<WhatsAppStatus>())
            override suspend fun isAccessGranted(isBusiness: Boolean) = false
            override suspend fun scanStatuses(isBusiness: Boolean) = Result.success(emptyList<WhatsAppStatus>())
            override suspend fun saveStatus(status: WhatsAppStatus, overwrite: Boolean): Result<String> {
                savedIds += status.id
                return if (status.id in failingIds) Result.failure(IllegalStateException("disk full"))
                else Result.success("content://media/${status.id}")
            }
        }
        val vm = StatusViewModel(
            CheckStatusPermissionUseCase(repo),
            ScanStatusesUseCase(repo),
            SaveStatusUseCase(repo),
            ObserveStatusesUseCase(repo),
            SaveStatusUriUseCase(settings)
        )
        val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.uiState.collect {} }
        try {
            val s1 = WhatsAppStatus("s1", "1.jpg", "content://1", StatusMediaType.IMAGE, 100L, 1000L, isSaved = false)
            val s2 = WhatsAppStatus("s2", "2.mp4", "content://2", StatusMediaType.VIDEO, 200L, 2000L, isSaved = false)
            val s3 = WhatsAppStatus("s3", "3.jpg", "content://3", StatusMediaType.IMAGE, 300L, 3000L, isSaved = true, savedPath = "content://saved/3")

            vm.saveStatuses(listOf(s1, s2, s3))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("s1", "s2"), savedIds)
            assertEquals("Saved 2 statuses to gallery!", vm.uiState.value.userMessage)

            vm.saveStatuses(listOf(s3))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Selected statuses are already saved.", vm.uiState.value.userMessage)

            failingIds += "s2"
            vm.saveStatuses(listOf(s1, s2))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Saved 1 of 2 statuses (1 failed)", vm.uiState.value.userMessage)
        } finally {
            collector.cancel()
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
