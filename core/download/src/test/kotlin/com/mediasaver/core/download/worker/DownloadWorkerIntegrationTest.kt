package com.mediasaver.core.download.worker

import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediasaver.core.database.MediaSaverDatabase
import com.mediasaver.core.download.muxing.DefaultMuxingEngine
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.storage.MediaStoreManager
import com.mediasaver.core.storage.TempFileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowStatFs
import java.io.ByteArrayOutputStream
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DownloadWorkerIntegrationTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var appDatabase: MediaSaverDatabase
    private lateinit var tempFileManager: TempFileManager
    private lateinit var mediaStoreManager: MediaStoreManager
    
    private lateinit var downloadWorker: DownloadWorker

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Robolectric reports zero free blocks unless the test models a writable volume.
        ShadowStatFs.registerStats(context.cacheDir, 100_000, 90_000, 90_000)
        mockWebServer = MockWebServer()
        mockWebServer.start()

        okHttpClient = OkHttpClient.Builder().build()

        appDatabase = Room.inMemoryDatabaseBuilder(
            context,
            MediaSaverDatabase::class.java
        ).allowMainThreadQueries().build()

        tempFileManager = TempFileManager(context)
        mediaStoreManager = MediaStoreManager(context)

        downloadWorker = DownloadWorker(
            okHttpClient = okHttpClient,
            downloadDao = appDatabase.downloadDao(),
            tempFileManager = tempFileManager,
            mediaStoreManager = mediaStoreManager,
            muxingEngine = DefaultMuxingEngine()
        )
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
        appDatabase.close()
    }

    @Test
    fun `successful progressive download correctly streams and saves to MediaStore`() = runTest {
        // Arrange
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val item = ContentUris.withAppendedId(collection, 1L)
        val output = ByteArrayOutputStream()
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        shadowOf(resolver).registerOutputStream(item, output)
        val testContent = "dummy_video_data".toByteArray()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setBody(okio.Buffer().write(testContent))
        )

        val downloadId = UUID.randomUUID().toString()
        val targetFileName = "test_video.mp4"
        val videoStream = StreamInfo(
            url = mockWebServer.url("/video.mp4").toString(),
            quality = "720p",
            format = "mp4",
            fileSize = testContent.size.toLong()
        )

        // Seed DB
        val entity = com.mediasaver.core.database.entity.DownloadEntity(
            id = downloadId,
            url = "https://youtube.com/watch?v=123",
            platform = "YOUTUBE",
            mediaId = "123",
            title = "Test",
            status = "QUEUED",
            createdAt = System.currentTimeMillis()
        )
        appDatabase.downloadDao().insert(entity)

        // Act
        val result = downloadWorker.executeDownload(
            downloadId = downloadId,
            targetFileName = targetFileName,
            platform = PlatformId.YOUTUBE,
            videoStream = videoStream
        )

        // Assert
        assertTrue("Download should succeed", result.isSuccess)
        
        // Verify DB state
        val updatedEntity = appDatabase.downloadDao().getById(downloadId)
        assertEquals("COMPLETED", updatedEntity?.status)
        assertEquals(item.toString(), result.getOrThrow())
        org.junit.Assert.assertArrayEquals(testContent, output.toByteArray())
        
        // Verify MediaStore URI format returned
        val uri = result.getOrNull()
        assertTrue(uri != null && uri.startsWith("content://"))
    }

    @Test
    fun `failed download due to 404 updates DB to FAILED state`() = runTest {
        // Arrange
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val downloadId = UUID.randomUUID().toString()
        val videoStream = StreamInfo(
            url = mockWebServer.url("/not_found.mp4").toString(),
            quality = "720p",
            format = "mp4"
        )

        val entity = com.mediasaver.core.database.entity.DownloadEntity(
            id = downloadId,
            url = "https://youtube.com/watch?v=123",
            platform = "YOUTUBE",
            mediaId = "123",
            title = "Test",
            status = "QUEUED",
            createdAt = System.currentTimeMillis()
        )
        appDatabase.downloadDao().insert(entity)

        // Act
        val result = downloadWorker.executeDownload(
            downloadId = downloadId,
            targetFileName = "test_video.mp4",
            platform = PlatformId.YOUTUBE,
            videoStream = videoStream
        )

        // Assert
        assertTrue("Download should fail", result.isFailure)
        
        // Verify DB state
        val updatedEntity = appDatabase.downloadDao().getById(downloadId)
        assertEquals("FAILED", updatedEntity?.status)
        assertTrue(updatedEntity?.errorType?.contains("DownloadFailed") == true)
    }
}
