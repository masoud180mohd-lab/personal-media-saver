package com.mediasaver.core.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import com.mediasaver.core.database.AppDatabase
import com.mediasaver.core.database.entity.DownloadEntity
import com.mediasaver.core.download.queue.DownloadQueueManager
import com.mediasaver.core.download.repository.DownloadRepositoryImpl
import com.mediasaver.core.download.worker.DownloadProgress
import com.mediasaver.core.download.worker.DownloadWorker
import com.mediasaver.core.model.DownloadStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

// A basic fake to simulate the worker's progress flow
class FakeDownloadWorker {
    val progressFlow = MutableSharedFlow<DownloadProgress>(replay = 1)
    suspend fun emitProgress(progress: DownloadProgress) {
        progressFlow.emit(progress)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DownloadRepositoryIntegrationTest {

    private lateinit var appDatabase: AppDatabase
    private lateinit var downloadRepository: DownloadRepositoryImpl
    
    // We mock the worker and queue manager since their logic is tested separately
    private lateinit var fakeWorkerProgressFlow: MutableSharedFlow<DownloadProgress>

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            
        fakeWorkerProgressFlow = MutableSharedFlow(replay = 1)
        
        // We use a real DownloadWorker with mock dependencies to satisfy the constructor
        // but we won't call executeDownload in this DB mapping test.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mockWebServer = okhttp3.mockwebserver.MockWebServer()
        val okHttpClient = okhttp3.OkHttpClient.Builder().build()
        val tempFileManager = com.mediasaver.core.storage.TempFileManager(context)
        val mediaStoreManager = com.mediasaver.core.storage.MediaStoreManager(context)
        val muxingEngine = com.mediasaver.core.download.muxing.MuxingEngine(context)

        val realWorker = DownloadWorker(
            okHttpClient = okHttpClient,
            downloadDao = appDatabase.downloadDao(),
            tempFileManager = tempFileManager,
            mediaStoreManager = mediaStoreManager,
            muxingEngine = muxingEngine
        )

        downloadRepository = DownloadRepositoryImpl(
            downloadQueueManager = object : DownloadQueueManager {
                override val activeTasksFlow = kotlinx.coroutines.flow.emptyFlow<List<com.mediasaver.core.model.DownloadTask>>()
                override val allTasksFlow = kotlinx.coroutines.flow.emptyFlow<List<com.mediasaver.core.model.DownloadTask>>()
                override suspend fun enqueue(task: com.mediasaver.core.model.DownloadTask, videoStream: com.mediasaver.core.model.StreamInfo, audioStream: com.mediasaver.core.model.StreamInfo?) {}
                override suspend fun cancel(downloadId: String) {}
                override suspend fun retry(downloadId: String) {}
            },
            downloadDao = appDatabase.downloadDao(),
            downloadWorker = realWorker
        )
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
}
