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
}
