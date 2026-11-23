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

        okHttpClient = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns(object : okhttp3.Dns {
            override fun lookup(hostname: String) = listOf(java.net.InetAddress.getLoopbackAddress())
        }).build()

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
        val testContent = byteArrayOf(0, 0, 0, 20) + "ftypisomfixture".toByteArray()
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
        assertEquals(testContent.size.toLong(), updatedEntity?.downloadedBytes)
        assertEquals(testContent.size.toLong(), updatedEntity?.totalBytes)
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
    private suspend fun transfer(id: String, bytes: ByteArray, type: String, format: String, audio: Boolean = false): Result<String> {
        val url = mockWebServer.url("/media").newBuilder().host("video.cdninstagram.com").build().toString()
        appDatabase.downloadDao().insert(com.mediasaver.core.database.entity.DownloadEntity(
            id = id, url = url, platform = "INSTAGRAM", mediaId = id, status = "QUEUED"))
        mockWebServer.enqueue(MockResponse().setHeader("Content-Type", type).setBody(okio.Buffer().write(bytes)))
        return downloadWorker.executeDownload(id, "media.$format", PlatformId.INSTAGRAM,
            StreamInfo(url, format = format, isAudioOnly = audio))
    }

    @Test fun `known CDN text and disguised binaries never publish`() = runTest {
        for ((index, type) in listOf("text/html", "application/json", "application/octet-stream", "video/mp4").withIndex()) {
            val id = "bad$index"
            val error = transfer(id, "<html>unavailable</html>".toByteArray(), type, "mp4").exceptionOrNull()
            assertTrue(error is com.mediasaver.core.model.error.AppError.DownloadFailed)
            assertEquals("FAILED", appDatabase.downloadDao().getById(id)?.status)
            assertTrue(!tempFileManager.createTempDownloadFile(id).exists())
        }
    }

    @Test fun `image and audio use their collections and repeated content reuses saved URI`() = runTest {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val audio = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val jpeg = byteArrayOf(-1, -40, -1, 0)
        val m4a = byteArrayOf(0,0,0,20) + "ftypM4A ".toByteArray()
        val mp3 = "ID3fixture".toByteArray()
        for ((index, data) in listOf(jpeg, m4a, mp3).withIndex()) {
            val expected = ContentUris.withAppendedId(if (index == 0) images else audio, index.toLong() + 1)
            val sink = ByteArrayOutputStream()
            shadowOf(resolver).registerOutputStream(expected, sink)
            val id = "media$index"
            val path = transfer(id, data, "application/octet-stream", listOf("jpg","m4a","mp3")[index], index > 0).getOrThrow()
            assertEquals(expected.toString(), path)
            org.junit.Assert.assertArrayEquals(data, sink.toByteArray())
            assertEquals(data.size.toLong(), appDatabase.downloadDao().getById(id)?.downloadedBytes)
            if (index == 0) assertEquals(path, transfer("copy", jpeg, "image/jpeg", "jpg").getOrThrow())
        }
    }

    @Test fun `CDN MIME aliases are accepted per media family while HTML spoofing fails`() = runTest {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val audio = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val mp4 = byteArrayOf(0, 0, 0, 20) + "ftypisomfixture".toByteArray()
        val jpeg = byteArrayOf(-1, -40, -1, 0)
        val webmOpus = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + "opus".toByteArray()
        val m4a = byteArrayOf(0, 0, 0, 20) + "ftypM4A ".toByteArray()

        val cases = listOf(
            Triple(videos, mp4, Triple("binary/octet-stream", "mp4", false)),
            Triple(images, jpeg, Triple("image/jpg", "jpg", false)),
            Triple(audio, webmOpus, Triple("audio/opus", "webm", true)),
            Triple(audio, m4a, Triple("audio/aac", "m4a", true))
        )
        for ((index, entry) in cases.withIndex()) {
            val (collection, bytes, meta) = entry
            val (contentType, format, isAudio) = meta
            val expected = ContentUris.withAppendedId(collection, index.toLong() + 1)
            val sink = ByteArrayOutputStream()
            shadowOf(resolver).registerOutputStream(expected, sink)
            val path = transfer("alias$index", bytes, contentType, format, isAudio).getOrThrow()
            assertEquals(expected.toString(), path)
            org.junit.Assert.assertArrayEquals(bytes, sink.toByteArray())
        }

        val spoofError = transfer("spoof", mp4, "text/html; charset=utf-8", "mp4", false).exceptionOrNull()
        assertTrue(spoofError is com.mediasaver.core.model.error.AppError.DownloadFailed)
    }
}
