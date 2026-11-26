package com.mediasaver.core.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.mediasaver.core.database.MediaSaverDatabase
import com.mediasaver.core.database.entity.DownloadEntity
import com.mediasaver.core.download.queue.DownloadQueueManager
import com.mediasaver.core.download.repository.DownloadRepositoryImpl
import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.download.muxing.DefaultMuxingEngine
import com.mediasaver.core.download.retry.RetryPolicy
import com.mediasaver.core.storage.MediaStoreManager
import com.mediasaver.core.storage.TempFileManager
import okhttp3.OkHttpClient
import com.mediasaver.core.download.worker.DownloadWorker
import com.mediasaver.core.model.DownloadStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DownloadRepositoryIntegrationTest {

    private lateinit var appDatabase: MediaSaverDatabase
    private lateinit var testQueue: DownloadQueueManager
    private lateinit var downloadRepository: DownloadRepositoryImpl
    
    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appDatabase = Room.inMemoryDatabaseBuilder(context, MediaSaverDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            
        val dao = appDatabase.downloadDao()
        val tempFiles = TempFileManager(context)
        val worker = DownloadWorker(OkHttpClient(), dao, tempFiles, MediaStoreManager(context), DefaultMuxingEngine())
        // Isolate the repository query from asynchronous recovery of this test's active row.
        val queueDao = object : DownloadDao by dao {
            override suspend fun findActiveDownloads(): List<DownloadEntity> = emptyList()
        }
        val queue = DownloadQueueManager(context, queueDao, appDatabase.streamOptionDao(), worker, tempFiles, RetryPolicy())
        testQueue = queue
        testQueue.maxConcurrentDownloads = 0
        downloadRepository = DownloadRepositoryImpl(queue, dao, worker)
    }

    @After
    fun teardown() {
        appDatabase.close()
    }

    @Test
    fun `getCompletedDownloads correctly maps Room entities and filters out active downloads`() = runTest {
        // Arrange
        val completedId = UUID.randomUUID().toString()
        val activeId = UUID.randomUUID().toString()
        
        val completedEntity = DownloadEntity(
            id = completedId,
            url = "https://youtube.com/watch?v=1",
            platform = "YOUTUBE",
            mediaId = "1",
            title = "Completed Video",
            status = "COMPLETED",
            createdAt = 1000L
        )
        
        val activeEntity = DownloadEntity(
            id = activeId,
            url = "https://youtube.com/watch?v=2",
            platform = "YOUTUBE",
            mediaId = "2",
            title = "Downloading Video",
            status = "DOWNLOADING",
            createdAt = 2000L
        )
        
        appDatabase.downloadDao().insertAll(listOf(completedEntity, activeEntity))

        // Act & Assert using Turbine
        downloadRepository.getCompletedDownloads().test {
            val firstEmission = awaitItem()
            assertEquals(1, firstEmission.size)
            assertEquals(completedId, firstEmission[0].id)
            assertEquals("Completed Video", firstEmission[0].title)
            assertEquals(DownloadStatus.COMPLETED, firstEmission[0].status)
            
            cancelAndIgnoreRemainingEvents()
        }
    }
    @Test fun `carousel siblings enqueue atomically while identical slide is deduplicated`() = runTest {
        val task = com.mediasaver.core.model.DownloadTask("one", "https://instagram.com/p/abc/",
            com.mediasaver.core.model.PlatformId.INSTAGRAM, "abc")
        val stream = com.mediasaver.core.model.StreamInfo("https://cdninstagram.com/one.jpg", mediaIndex = 0)
        testQueue.enqueue(task, stream)
        testQueue.enqueue(task.copy(id = "two"), stream.copy(mediaIndex = 1))
        assertEquals("abc#slide_1", appDatabase.downloadDao().getById("one")?.mediaId)
        assertEquals("abc#slide_2", appDatabase.downloadDao().getById("two")?.mediaId)
        assertEquals(1, appDatabase.streamOptionDao().getByDownloadId("two").size)
        val duplicate = runCatching { testQueue.enqueue(task.copy(id = "copy"), stream) }
        org.junit.Assert.assertTrue(duplicate.isFailure)
        org.junit.Assert.assertNull(appDatabase.downloadDao().getById("copy"))
        val audio = com.mediasaver.core.model.StreamInfo("https://audio", format = "m4a", isAudioOnly = true)
        testQueue.enqueue(task.copy(id = "audio", mediaId = "different"), audio)
        org.junit.Assert.assertTrue(appDatabase.streamOptionDao().getByDownloadId("audio").single().isAudioOnly)
    }

    @Test fun `invalid stream rolls back its task and status hash is persisted atomically`() = runTest {
        val task = DownloadEntity(id = "atomic", url = "url", mediaId = "m", platform = "YOUTUBE", status = "QUEUED")
        val bad = com.mediasaver.core.database.entity.StreamOptionEntity("option", "missing-parent", "stream")
        val result = runCatching { appDatabase.streamOptionDao().enqueue(task, listOf(bad)) }
        org.junit.Assert.assertTrue(result.isFailure)
        org.junit.Assert.assertNull(appDatabase.downloadDao().getById(task.id))
        val dao = appDatabase.whatsappStatusDao()
        dao.insert(com.mediasaver.core.database.entity.WhatsAppStatusEntity("status", "test.jpg",
            "content://source", "IMAGE", 10, 1, ""))
        dao.markSaved("status", "content://gallery/item", "computed-sha")
        assertEquals("content://gallery/item", dao.getByFileHash("computed-sha")?.savedPath)
        org.junit.Assert.assertTrue(dao.getByFileHash("computed-sha")!!.isSaved)
    }

    @Test
    fun `queue syncs concurrency and enforces wifiOnlyDownload from SettingsRepository`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = appDatabase.downloadDao()
        val tempFiles = TempFileManager(context)
        val worker = DownloadWorker(OkHttpClient(), dao, tempFiles, MediaStoreManager(context), DefaultMuxingEngine())
        val queueDao = object : DownloadDao by dao {
            override suspend fun findActiveDownloads(): List<DownloadEntity> = emptyList()
        }

        val currentSettings = kotlinx.coroutines.flow.MutableStateFlow(
            com.mediasaver.core.model.AppSettings(maxConcurrentDownloads = 5, wifiOnlyDownload = true)
        )
        var wifiConnected = false
        val fakeSettingsRepo = object : com.mediasaver.core.domain.repository.SettingsRepository {
            override val settings = currentSettings
            override suspend fun getSettings() = currentSettings.value
            override suspend fun updatePreferredQuality(quality: String) {}
            override suspend fun updateDownloadDirectoryUri(uri: String?) {}
            override suspend fun updateMaxConcurrentDownloads(limit: Int) {}
            override suspend fun updateWifiOnlyDownload(wifiOnly: Boolean) {}
            override suspend fun updateMaxDownloadSizeMb(maxSizeMb: Int) {}
            override suspend fun updateWhatsAppStatusUri(uri: String?) {}
            override suspend fun updateWhatsAppBusinessStatusUri(uri: String?) {}
            override suspend fun updateSaveDownloadHistory(enabled: Boolean) {}
            override suspend fun updateAutoDeleteTempFiles(enabled: Boolean) {}
            override suspend fun updateThemeMode(themeMode: com.mediasaver.core.model.ThemeMode) {}
            override suspend fun updateLanguageCode(languageCode: String) {}
            override suspend fun resetToDefaults() {}
        }

        val managedQueue = DownloadQueueManager(
            context = context,
            downloadDao = queueDao,
            streamOptionDao = appDatabase.streamOptionDao(),
            downloadWorker = worker,
            tempFileManager = tempFiles,
            retryPolicy = RetryPolicy(),
            settingsRepository = fakeSettingsRepo,
            isWifiConnectedProvider = { wifiConnected }
        )

        val task = com.mediasaver.core.model.DownloadTask(
            id = "wifi-blocked",
            url = "https://youtube.com/watch?v=wifi",
            platform = com.mediasaver.core.model.PlatformId.YOUTUBE,
            mediaId = "wifi"
        )
        val stream = com.mediasaver.core.model.StreamInfo("https://cdn.example/video.mp4", quality = "720p")

        val enqueueBlocked = runCatching { managedQueue.enqueue(task, stream) }
        org.junit.Assert.assertTrue(enqueueBlocked.exceptionOrNull() is com.mediasaver.core.model.error.AppError.NetworkError)
        assertEquals(5, managedQueue.maxConcurrentDownloads)
        org.junit.Assert.assertTrue(managedQueue.wifiOnlyDownload)

        val retryBlocked = runCatching { managedQueue.retry("existing-failed") }
        org.junit.Assert.assertTrue(retryBlocked.exceptionOrNull() is com.mediasaver.core.model.error.AppError.NetworkError)

        // When Wi-Fi connects, enqueue succeeds
        wifiConnected = true
        managedQueue.enqueue(task, stream)
        org.junit.Assert.assertNotNull(dao.getById("wifi-blocked"))

        // Disconnecting Wi-Fi and toggling wifiOnlyDownload off also permits enqueue
        wifiConnected = false
        currentSettings.value = currentSettings.value.copy(maxConcurrentDownloads = 2, wifiOnlyDownload = false)
        managedQueue.enqueue(task.copy(id = "mobile-allowed", mediaId = "mobile"), stream)
        org.junit.Assert.assertNotNull(dao.getById("mobile-allowed"))
        assertEquals(2, managedQueue.maxConcurrentDownloads)
        org.junit.Assert.assertFalse(managedQueue.wifiOnlyDownload)
    }

    @Test
    fun `video and audio-only streams for same mediaId coexist while duplicate audio is blocked`() = runTest {
        val task = com.mediasaver.core.model.DownloadTask(
            id = "vid-task",
            url = "https://www.youtube.com/watch?v=same123",
            platform = com.mediasaver.core.model.PlatformId.YOUTUBE,
            mediaId = "same123"
        )
        val videoStream = com.mediasaver.core.model.StreamInfo(
            url = "https://cdn.example/video.mp4",
            quality = "720p",
            format = "mp4",
            mimeType = "video/mp4"
        )
        val audioStream = com.mediasaver.core.model.StreamInfo(
            url = "https://cdn.example/audio.m4a",
            quality = "128 kbps",
            format = "m4a",
            mimeType = "audio/mp4",
            isAudioOnly = true
        )

        testQueue.enqueue(task, videoStream)
        testQueue.enqueue(task.copy(id = "aud-task-1"), audioStream)

        assertEquals("same123", appDatabase.downloadDao().getById("vid-task")?.mediaId)
        assertEquals("same123#audio", appDatabase.downloadDao().getById("aud-task-1")?.mediaId)

        val duplicateAudio = runCatching {
            testQueue.enqueue(task.copy(id = "aud-task-2"), audioStream)
        }
        org.junit.Assert.assertTrue(duplicateAudio.exceptionOrNull() is com.mediasaver.core.model.error.AppError.DuplicateDownload)
        org.junit.Assert.assertNull(appDatabase.downloadDao().getById("aud-task-2"))
    }

    @Test
    fun `pause, resume, bulk queue actions, startup recovery for PAUSED tasks, and wifiOnly enforcement work`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dao = appDatabase.downloadDao()
        val tempFiles = TempFileManager(context)
        val worker = DownloadWorker(OkHttpClient(), dao, tempFiles, MediaStoreManager(context), DefaultMuxingEngine())
        val queue = DownloadQueueManager(context, dao, appDatabase.streamOptionDao(), worker, tempFiles, RetryPolicy())
        queue.maxConcurrentDownloads = 0

        dao.insertAll(
            listOf(
                DownloadEntity(
                    id = "q-active", url = "https://youtube.com/watch?v=q1",
                    platform = "YOUTUBE", mediaId = "q1", status = "DOWNLOADING", createdAt = 1L
                ),
                DownloadEntity(
                    id = "q-queued", url = "https://youtube.com/watch?v=q2",
                    platform = "YOUTUBE", mediaId = "q2", status = "QUEUED", createdAt = 2L
                ),
                DownloadEntity(
                    id = "q-failed", url = "https://youtube.com/watch?v=q3",
                    platform = "YOUTUBE", mediaId = "q3", status = "FAILED", retryCount = 2, createdAt = 3L
                )
            )
        )

        val pausedPartial = tempFiles.createTempDownloadFile("q-active")
        pausedPartial.writeBytes(byteArrayOf(1, 2, 3, 4))
        pausedPartial.setLastModified(0L)
        val orphanFile = tempFiles.createTempDownloadFile("orphan-old")
        orphanFile.writeBytes(byteArrayOf(9, 9))
        orphanFile.setLastModified(0L)

        // Pause single downloading task and verify startup recovery preserves its partial file
        queue.pause("q-active")
        assertEquals("PAUSED", dao.getById("q-active")?.status)

        queue.performStartupRecovery()
        org.junit.Assert.assertTrue(pausedPartial.exists())
        org.junit.Assert.assertFalse(orphanFile.exists())

        // Single resume returns PAUSED -> QUEUED
        queue.resume("q-active")
        assertEquals("QUEUED", dao.getById("q-active")?.status)

        // Bulk pauseAll -> resumeAll -> retryAllFailed -> cancelAll
        queue.pauseAll()
        assertEquals("PAUSED", dao.getById("q-active")?.status)
        assertEquals("PAUSED", dao.getById("q-queued")?.status)
        assertEquals("FAILED", dao.getById("q-failed")?.status)

        queue.resumeAll()
        assertEquals("QUEUED", dao.getById("q-active")?.status)
        assertEquals("QUEUED", dao.getById("q-queued")?.status)

        queue.retryAllFailed()
        val retried = dao.getById("q-failed")
        assertEquals("QUEUED", retried?.status)
        assertEquals(0, retried?.retryCount)

        queue.cancelAll()
        assertEquals("CANCELLED", dao.getById("q-active")?.status)
        assertEquals("CANCELLED", dao.getById("q-queued")?.status)
        assertEquals("CANCELLED", dao.getById("q-failed")?.status)
        org.junit.Assert.assertFalse(pausedPartial.exists())

        // Verify Wi-Fi Only blocks resume and resumeAll when offline/mobile
        dao.insert(
            DownloadEntity(
                id = "wifi-paused", url = "https://youtube.com/watch?v=wp",
                platform = "YOUTUBE", mediaId = "wp", status = "PAUSED"
            )
        )
        val wifiSettingsFlow = kotlinx.coroutines.flow.MutableStateFlow(
            com.mediasaver.core.model.AppSettings(wifiOnlyDownload = true)
        )
        val fakeSettingsRepo = object : com.mediasaver.core.domain.repository.SettingsRepository {
            override val settings = wifiSettingsFlow
            override suspend fun getSettings() = wifiSettingsFlow.value
            override suspend fun updatePreferredQuality(quality: String) {}
            override suspend fun updateDownloadDirectoryUri(uri: String?) {}
            override suspend fun updateMaxConcurrentDownloads(limit: Int) {}
            override suspend fun updateWifiOnlyDownload(wifiOnly: Boolean) {}
            override suspend fun updateMaxDownloadSizeMb(maxSizeMb: Int) {}
            override suspend fun updateWhatsAppStatusUri(uri: String?) {}
            override suspend fun updateWhatsAppBusinessStatusUri(uri: String?) {}
            override suspend fun updateSaveDownloadHistory(enabled: Boolean) {}
            override suspend fun updateAutoDeleteTempFiles(enabled: Boolean) {}
            override suspend fun updateThemeMode(themeMode: com.mediasaver.core.model.ThemeMode) {}
            override suspend fun updateLanguageCode(languageCode: String) {}
            override suspend fun resetToDefaults() {}
        }
        val wifiGuardedQueue = DownloadQueueManager(
            context = context,
            downloadDao = dao,
            streamOptionDao = appDatabase.streamOptionDao(),
            downloadWorker = worker,
            tempFileManager = tempFiles,
            retryPolicy = RetryPolicy(),
            settingsRepository = fakeSettingsRepo,
            isWifiConnectedProvider = { false }
        )

        val resumeBlocked = runCatching { wifiGuardedQueue.resume("wifi-paused") }
        val resumeAllBlocked = runCatching { wifiGuardedQueue.resumeAll() }
        org.junit.Assert.assertTrue(resumeBlocked.exceptionOrNull() is com.mediasaver.core.model.error.AppError.NetworkError)
        org.junit.Assert.assertTrue(resumeAllBlocked.exceptionOrNull() is com.mediasaver.core.model.error.AppError.NetworkError)
        assertEquals("PAUSED", dao.getById("wifi-paused")?.status)
    }

    @Test
    fun `DownloadTask subfolder persists in Room description and round-trips through getAllDownloads`() = runTest {
        val task = com.mediasaver.core.model.DownloadTask(
            id = "subfolder-roundtrip",
            url = "https://www.youtube.com/watch?v=subrt",
            platform = com.mediasaver.core.model.PlatformId.YOUTUBE,
            mediaId = "subrt",
            title = "001 - Intro",
            subfolder = "Lugha (Nah-wu)"
        )
        val stream = com.mediasaver.core.model.StreamInfo(
            url = "https://cdn.example/video.mp4",
            quality = "720p",
            format = "mp4",
            mimeType = "video/mp4"
        )

        testQueue.enqueue(task, stream)

        assertEquals("folder:Lugha (Nah-wu)", appDatabase.downloadDao().getById("subfolder-roundtrip")?.description)
        downloadRepository.getAllDownloads().test {
            val emitted = awaitItem().firstOrNull { it.id == "subfolder-roundtrip" }
            assertEquals("Lugha (Nah-wu)", emitted?.subfolder)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
