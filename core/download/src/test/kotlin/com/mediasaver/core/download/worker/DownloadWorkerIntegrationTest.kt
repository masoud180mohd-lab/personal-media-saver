package com.mediasaver.core.download.worker

import android.content.Context
import android.content.ContentUris
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediasaver.core.database.MediaSaverDatabase
import com.mediasaver.core.download.muxing.DefaultMuxingEngine
import com.mediasaver.core.network.EphemeralStreamHeaders
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.storage.MediaStoreManager
import com.mediasaver.core.storage.TempFileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
    private lateinit var ephemeralHeaders: EphemeralStreamHeaders

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
        ephemeralHeaders = EphemeralStreamHeaders()

        downloadWorker = DownloadWorker(
            okHttpClient = okHttpClient,
            downloadDao = appDatabase.downloadDao(),
            tempFileManager = tempFileManager,
            mediaStoreManager = mediaStoreManager,
            muxingEngine = DefaultMuxingEngine(),
            ephemeralHeaders = ephemeralHeaders
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
            assertEquals(listOf("image/jpeg", "audio/mp4", "audio/mpeg")[index], appDatabase.downloadDao().getById(id)?.mimeType)
            if (index == 0) {
                assertEquals(path, transfer("copy", jpeg, "image/jpeg", "jpg").getOrThrow())
                assertEquals("image/jpeg", appDatabase.downloadDao().getById("copy")?.mimeType)
            }
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

    @Test fun `YouTube 403 non-ranged GET falls back to bounded 1MB Range chunks`() = runTest {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val expected = ContentUris.withAppendedId(videos, 1L)
        val sink = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(expected, sink)

        val payload = ByteArray(1_100_000) { index -> (index % 251).toByte() }
        val header = byteArrayOf(0, 0, 0, 20) + "ftypisom".toByteArray()
        System.arraycopy(header, 0, payload, 0, header.size)

        val rangeRequests = mutableListOf<String?>()
        mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                rangeRequests += range
                if (range == null) return MockResponse().setResponseCode(403)
                val spec = range.removePrefix("bytes=").split("-")
                val start = spec[0].toInt()
                val end = minOf(spec[1].toInt(), payload.size - 1)
                val slice = payload.copyOfRange(start, end + 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setBody(okio.Buffer().write(slice))
            }
        }

        val url = mockWebServer.url("/videoplayback?clen=${payload.size}&c=ANDROID")
            .newBuilder().host("rr1---sn.googlevideo.com").build().toString()
        val id = "yt-range"
        appDatabase.downloadDao().insert(
            com.mediasaver.core.database.entity.DownloadEntity(
                id = id, url = url, platform = "YOUTUBE", mediaId = id, status = "QUEUED"
            )
        )

        val path = downloadWorker.executeDownload(
            id, "yt_test.mp4", PlatformId.YOUTUBE,
            StreamInfo(url, quality = "360p", format = "mp4", mimeType = "video/mp4")
        ).getOrThrow()

        assertEquals(expected.toString(), path)
        org.junit.Assert.assertArrayEquals(payload, sink.toByteArray())
        assertEquals(listOf(null, "bytes=0-1048575", "bytes=1048576-1099999"), rangeRequests)
    }

    @Test fun `TikTok ephemeral stream headers and ID3 audio with mislabeled video mp4 Content-Type succeed`() = runTest {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val videos = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val audioCollection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val videoUri = ContentUris.withAppendedId(videos, 1L)
        val audioUri = ContentUris.withAppendedId(audioCollection, 2L)
        val videoSink = ByteArrayOutputStream()
        val audioSink = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(videoUri, videoSink)
        shadowOf(resolver).registerOutputStream(audioUri, audioSink)

        val mp4Bytes = byteArrayOf(0, 0, 0, 20) + "ftypisomtiktok".toByteArray()
        val mp3Bytes = "ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000audio-bytes".toByteArray()

        val videoUrl = mockWebServer.url("/video.mp4").newBuilder().host("v16-webapp-prime.tiktok.com").build().toString()
        val audioUrl = mockWebServer.url("/music.mp3").newBuilder().host("v16m.tiktokcdn.com").build().toString()
        ephemeralHeaders.put(videoUrl, mapOf("Cookie" to "tt_chain_token=ephemeral123"))

        val recordedCookies = mutableMapOf<String, String?>()
        mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val reqPath = request.path.orEmpty()
                recordedCookies[reqPath] = request.getHeader("Cookie")
                return when {
                    reqPath.startsWith("/video.mp4") -> {
                        if (request.getHeader("Cookie") != "tt_chain_token=ephemeral123") {
                            MockResponse().setResponseCode(403)
                        } else {
                            MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(mp4Bytes))
                        }
                    }
                    reqPath.startsWith("/music.mp3") ->
                        MockResponse().setHeader("Content-Type", "video/mp4").setBody(okio.Buffer().write(mp3Bytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        appDatabase.downloadDao().insert(
            com.mediasaver.core.database.entity.DownloadEntity(
                id = "tt-vid", url = videoUrl, platform = "TIKTOK", mediaId = "tt1", status = "QUEUED"
            )
        )
        appDatabase.downloadDao().insert(
            com.mediasaver.core.database.entity.DownloadEntity(
                id = "tt-aud", url = audioUrl, platform = "TIKTOK", mediaId = "tt2", status = "QUEUED"
            )
        )

        val vidPath = downloadWorker.executeDownload(
            "tt-vid", "tt.mp4", PlatformId.TIKTOK,
            StreamInfo(videoUrl, quality = "HD (No Watermark)", format = "mp4", mimeType = "video/mp4")
        ).getOrThrow()
        val audPath = downloadWorker.executeDownload(
            "tt-aud", "tt.mp3", PlatformId.TIKTOK,
            StreamInfo(audioUrl, quality = "Audio Only", format = "mp3", mimeType = "audio/mpeg", isAudioOnly = true)
        ).getOrThrow()

        assertEquals(videoUri.toString(), vidPath)
        assertEquals(audioUri.toString(), audPath)
        assertEquals("tt_chain_token=ephemeral123", recordedCookies["/video.mp4"])
        assertEquals("video/mp4", appDatabase.downloadDao().getById("tt-vid")?.mimeType)
        assertEquals("audio/mpeg", appDatabase.downloadDao().getById("tt-aud")?.mimeType)
        org.junit.Assert.assertArrayEquals(mp4Bytes, videoSink.toByteArray())
        org.junit.Assert.assertArrayEquals(mp3Bytes, audioSink.toByteArray())
    }

    @Test
    fun `audio-only webm falls back to MediaStore Downloads when MediaStore Audio rejects audio-webm and m4a saves via MediaStore Audio`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val audioCollection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val audioUri = ContentUris.withAppendedId(audioCollection, 1L)
        val audioSink = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(audioUri, audioSink)

        val m4aBytes = byteArrayOf(0, 0, 0, 20) + "ftypM4A fixture!".toByteArray()
        val webmBytes = byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(16) { it.toByte() }
        val m4aUrl = mockWebServer.url("/track.m4a").toString()
        val webmUrl = mockWebServer.url("/track.webm").toString()

        mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return when (request.path) {
                    "/track.m4a" -> MockResponse().setHeader("Content-Type", "audio/mp4").setBody(okio.Buffer().write(m4aBytes))
                    "/track.webm" -> MockResponse().setHeader("Content-Type", "audio/webm").setBody(okio.Buffer().write(webmBytes))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        appDatabase.downloadDao().insert(
            com.mediasaver.core.database.entity.DownloadEntity(
                id = "yt-m4a", url = m4aUrl, platform = "YOUTUBE", mediaId = "m1#audio", status = "QUEUED"
            )
        )
        appDatabase.downloadDao().insert(
            com.mediasaver.core.database.entity.DownloadEntity(
                id = "yt-webm", url = webmUrl, platform = "YOUTUBE", mediaId = "m2#audio", status = "QUEUED"
            )
        )

        val m4aPath = downloadWorker.executeDownload(
            "yt-m4a", "Track 1.m4a", PlatformId.YOUTUBE,
            StreamInfo(m4aUrl, quality = "128 kbps", format = "m4a", mimeType = "audio/mp4", isAudioOnly = true)
        ).getOrThrow()

        assertEquals(audioUri.toString(), m4aPath)
        assertEquals("audio/mp4", appDatabase.downloadDao().getById("yt-m4a")?.mimeType)
        org.junit.Assert.assertArrayEquals(m4aBytes, audioSink.toByteArray())

        val downloadsCollection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val downloadsUri = ContentUris.withAppendedId(downloadsCollection, 2L)
        val fallbackFile = java.io.File.createTempFile("fallback-webm", ".webm", context.cacheDir)
        var audioInsertAttempted = false
        val provider = object : android.content.ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: android.net.Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): android.database.Cursor? = null
            override fun getType(uri: android.net.Uri): String? = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? {
                val mime = values?.getAsString(MediaStore.MediaColumns.MIME_TYPE)
                if (uri == audioCollection && mime == "audio/webm") {
                    audioInsertAttempted = true
                    throw IllegalArgumentException("Unsupported MIME type audio/webm")
                }
                if (uri == downloadsCollection) {
                    return downloadsUri
                }
                return null
            }
            override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int =
                if (uri == downloadsUri) 1 else 0
            override fun update(
                uri: android.net.Uri,
                values: android.content.ContentValues?,
                selection: String?,
                selectionArgs: Array<out String>?
            ): Int = if (uri == downloadsUri) 1 else 0
            override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor? {
                if (uri == downloadsUri) {
                    return android.os.ParcelFileDescriptor.open(
                        fallbackFile,
                        android.os.ParcelFileDescriptor.MODE_CREATE or
                            android.os.ParcelFileDescriptor.MODE_TRUNCATE or
                            android.os.ParcelFileDescriptor.MODE_READ_WRITE
                    )
                }
                return super.openFile(uri, mode)
            }
        }
        provider.attachInfo(
            context,
            android.content.pm.ProviderInfo().apply {
                authority = MediaStore.AUTHORITY
                exported = true
            }
        )
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val webmPath = downloadWorker.executeDownload(
            "yt-webm", "Track 2.webm", PlatformId.YOUTUBE,
            StreamInfo(webmUrl, quality = "160 kbps", format = "webm", mimeType = "audio/webm", isAudioOnly = true)
        ).getOrThrow()

        assertTrue(audioInsertAttempted)
        assertEquals(downloadsUri.toString(), webmPath)
        assertEquals("audio/webm", appDatabase.downloadDao().getById("yt-webm")?.mimeType)
        org.junit.Assert.assertArrayEquals(webmBytes, fallbackFile.readBytes())
    }

    @Test
    fun `resumes partial temp file via HTTP 206 Range and saves combined payload`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val videoUri = ContentUris.withAppendedId(videoCollection, 1L)
        val videoSink = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(videoUri, videoSink)

        val fullBytes = byteArrayOf(0, 0, 0, 20) + "ftypmp42".toByteArray() + ByteArray(48) { (it + 1).toByte() }
        val splitOffset = 20
        val partialBytes = fullBytes.copyOfRange(0, splitOffset)
        val remainingBytes = fullBytes.copyOfRange(splitOffset, fullBytes.size)

        val tempFile = com.mediasaver.core.storage.TempFileManager(context).createTempDownloadFile("resume-206")
        tempFile.writeBytes(partialBytes)

        val observedRanges = mutableListOf<String?>()
        val videoUrl = mockWebServer.url("/resumable.mp4").toString()
        mockWebServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                observedRanges.add(request.getHeader("Range"))
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Range", "bytes $splitOffset-${fullBytes.size - 1}/${fullBytes.size}")
                    .setBody(okio.Buffer().write(remainingBytes))
            }
        }

        appDatabase.downloadDao().insert(
            com.mediasaver.core.database.entity.DownloadEntity(
                id = "resume-206",
                url = videoUrl,
                platform = "YOUTUBE",
                mediaId = "resume206",
                status = "QUEUED"
            )
        )

        val savedPath = downloadWorker.executeDownload(
            "resume-206",
            "Resumed Video.mp4",
            PlatformId.YOUTUBE,
            StreamInfo(videoUrl, quality = "720p", format = "mp4", mimeType = "video/mp4")
        ).getOrThrow()

        assertEquals(listOf("bytes=$splitOffset-"), observedRanges)
        assertEquals(videoUri.toString(), savedPath)
        org.junit.Assert.assertArrayEquals(fullBytes, videoSink.toByteArray())
    }

    @Test
    fun `falls back cleanly to full HTTP 200 overwrite when server ignores Range during resume`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        val videoCollection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val videoUri = ContentUris.withAppendedId(videoCollection, 1L)
        val videoSink = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(videoUri, videoSink)

        val fullBytes = byteArrayOf(0, 0, 0, 20) + "ftypmp42".toByteArray() + ByteArray(48) { (it + 2).toByte() }
        val tempFile = com.mediasaver.core.storage.TempFileManager(context).createTempDownloadFile("resume-200")
        tempFile.writeBytes(fullBytes.copyOfRange(0, 20))

        val videoUrl = mockWebServer.url("/full-200.mp4").toString()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setBody(okio.Buffer().write(fullBytes))
        )

        appDatabase.downloadDao().insert(
            com.mediasaver.core.database.entity.DownloadEntity(
                id = "resume-200",
                url = videoUrl,
                platform = "YOUTUBE",
                mediaId = "resume200",
                status = "QUEUED"
            )
        )

        val savedPath = downloadWorker.executeDownload(
            "resume-200",
            "Full Fallback.mp4",
            PlatformId.YOUTUBE,
            StreamInfo(videoUrl, quality = "720p", format = "mp4", mimeType = "video/mp4")
        ).getOrThrow()

        assertEquals(videoUri.toString(), savedPath)
        org.junit.Assert.assertArrayEquals(fullBytes, videoSink.toByteArray())
    }

    @Test
    fun `pause request cancels transfer while preserving partial temp file and setting PAUSED status`() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempFile = com.mediasaver.core.storage.TempFileManager(context).createTempDownloadFile("pause-mid")
        tempFile.delete()

        val initialHeader = byteArrayOf(0, 0, 0, 20) + "ftypmp42".toByteArray() + ByteArray(12) { 7 }
        val trailingPayload = ByteArray(16 * 1024) { 9 }
        val videoUrl = mockWebServer.url("/slow.mp4").toString()
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "video/mp4")
                .setBody(okio.Buffer().write(initialHeader).write(trailingPayload))
                .throttleBody(64, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
        )

        appDatabase.downloadDao().insert(
            com.mediasaver.core.database.entity.DownloadEntity(
                id = "pause-mid",
                url = videoUrl,
                platform = "YOUTUBE",
                mediaId = "pausemid",
                totalBytes = (initialHeader.size + trailingPayload.size).toLong(),
                status = "QUEUED"
            )
        )

        val transferJob = launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                downloadWorker.executeDownload(
                    "pause-mid",
                    "Pause Mid.mp4",
                    PlatformId.YOUTUBE,
                    StreamInfo(videoUrl, quality = "720p", format = "mp4", mimeType = "video/mp4")
                )
            }
        }

        kotlinx.coroutines.withTimeout(5000L) {
            while (!tempFile.exists() || tempFile.length() < 12L) {
                kotlinx.coroutines.delay(15L)
            }
        }

        downloadWorker.markPauseRequested("pause-mid")
        transferJob.cancel()
        transferJob.join()

        assertTrue(tempFile.exists())
        assertTrue(tempFile.length() >= 12L)
        val persisted = appDatabase.downloadDao().getById("pause-mid")
        assertEquals("PAUSED", persisted?.status)
        assertTrue((persisted?.downloadedBytes ?: 0L) >= 12L)
    }

    @Test
    fun `executeDownload writes subfolder into MediaStore RELATIVE_PATH and sanitizes path traversal`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioCollection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val audioUri1 = ContentUris.withAppendedId(audioCollection, 101L)
        val audioUri2 = ContentUris.withAppendedId(audioCollection, 102L)
        val sinkFile1 = java.io.File.createTempFile("sub1", ".m4a", context.cacheDir)
        val sinkFile2 = java.io.File.createTempFile("sub2", ".m4a", context.cacheDir)
        val insertedRelativePaths = mutableListOf<String?>()
        var insertSeq = 0

        val provider = object : android.content.ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: android.net.Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): android.database.Cursor? = null
            override fun getType(uri: android.net.Uri): String? = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? {
                insertedRelativePaths.add(values?.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
                insertSeq++
                return if (insertSeq == 1) audioUri1 else audioUri2
            }
            override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1
            override fun update(
                uri: android.net.Uri,
                values: android.content.ContentValues?,
                selection: String?,
                selectionArgs: Array<out String>?
            ): Int = 1
            override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor? {
                val target = if (uri == audioUri1) sinkFile1 else sinkFile2
                return android.os.ParcelFileDescriptor.open(
                    target,
                    android.os.ParcelFileDescriptor.MODE_CREATE or
                        android.os.ParcelFileDescriptor.MODE_TRUNCATE or
                        android.os.ParcelFileDescriptor.MODE_READ_WRITE
                )
            }
        }
        provider.attachInfo(
            context,
            android.content.pm.ProviderInfo().apply {
                authority = MediaStore.AUTHORITY
                exported = true
            }
        )
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val m4aBytes1 = byteArrayOf(0, 0, 0, 20) + "ftypM4A lesson1!".toByteArray()
        val m4aBytes2 = byteArrayOf(0, 0, 0, 20) + "ftypM4A lesson2!".toByteArray()
        val url1 = mockWebServer.url("/sub1.m4a").toString()
        val url2 = mockWebServer.url("/sub2.m4a").toString()
        mockWebServer.enqueue(MockResponse().setHeader("Content-Type", "audio/mp4").setBody(okio.Buffer().write(m4aBytes1)))
        mockWebServer.enqueue(MockResponse().setHeader("Content-Type", "audio/mp4").setBody(okio.Buffer().write(m4aBytes2)))

        appDatabase.downloadDao().insertAll(
            listOf(
                com.mediasaver.core.database.entity.DownloadEntity(
                    id = "sub-dl-1", url = url1, platform = "YOUTUBE", mediaId = "sub1#audio", status = "QUEUED"
                ),
                com.mediasaver.core.database.entity.DownloadEntity(
                    id = "sub-dl-2", url = url2, platform = "YOUTUBE", mediaId = "sub2#audio", status = "QUEUED"
                )
            )
        )

        downloadWorker.executeDownload(
            "sub-dl-1", "001 - Darsa.m4a", PlatformId.YOUTUBE,
            StreamInfo(url1, quality = "128 kbps", format = "m4a", mimeType = "audio/mp4", isAudioOnly = true),
            subfolder = "Lugha (Nah-wu)"
        ).getOrThrow()

        downloadWorker.executeDownload(
            "sub-dl-2", "002 - Darsa.m4a", PlatformId.YOUTUBE,
            StreamInfo(url2, quality = "128 kbps", format = "m4a", mimeType = "audio/mp4", isAudioOnly = true),
            subfolder = "../secret/folder"
        ).getOrThrow()

        assertEquals(2, insertedRelativePaths.size)
        assertEquals("Music/MediaSaver/YouTube/Lugha (Nah-wu)", insertedRelativePaths[0])
        val sanitizedPath = requireNotNull(insertedRelativePaths[1])
        assertTrue(sanitizedPath.startsWith("Music/MediaSaver/YouTube/"))
        org.junit.Assert.assertFalse(sanitizedPath.contains(".."))
        assertEquals(4, sanitizedPath.split('/').size)
    }

    @Test
    fun `audio download embeds ID3-like M4A atoms and JPEG cover art plus writes MediaStore audio columns`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sourceM4a = java.io.File(context.cacheDir, "source_tag_test.m4a")
        val mediaPayload = "M4A_INTEGRATION_PAYLOAD_XYZ".toByteArray()
        com.mediasaver.core.download.metadata.Mp4MetadataTaggerTest.writeSyntheticMp4(
            dest = sourceM4a,
            moovBeforeMdat = true,
            mediaPayload = mediaPayload
        )
        val rawM4aBytes = sourceM4a.readBytes()
        sourceM4a.delete()

        val jpegCover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x07, 0x08, 0x09)
        val sinkFile = java.io.File(context.cacheDir, "published_tagged_audio.m4a")
        val audioUri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            701L
        )
        var capturedValues: android.content.ContentValues? = null

        val provider = object : android.content.ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: android.net.Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): android.database.Cursor? = null
            override fun getType(uri: android.net.Uri): String? = null
            override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? {
                capturedValues = values?.let { android.content.ContentValues(it) }
                return audioUri
            }
            override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1
            override fun update(
                uri: android.net.Uri,
                values: android.content.ContentValues?,
                selection: String?,
                selectionArgs: Array<out String>?
            ): Int = 1
            override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor? {
                return android.os.ParcelFileDescriptor.open(
                    sinkFile,
                    android.os.ParcelFileDescriptor.MODE_CREATE or
                        android.os.ParcelFileDescriptor.MODE_TRUNCATE or
                        android.os.ParcelFileDescriptor.MODE_READ_WRITE
                )
            }
        }
        provider.attachInfo(
            context,
            android.content.pm.ProviderInfo().apply {
                authority = MediaStore.AUTHORITY
                exported = true
            }
        )
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

        val audioUrl = mockWebServer.url("/lesson04.m4a").toString()
        val coverServer = MockWebServer()
        coverServer.start()
        try {
            mockWebServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "audio/mp4")
                    .setBody(okio.Buffer().write(rawM4aBytes))
            )
            coverServer.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "image/jpeg")
                    .setBody(okio.Buffer().write(jpegCover))
            )

            val coverClient = OkHttpClient.Builder().addInterceptor { chain ->
                val original = chain.request()
                val local = coverServer.url("/").newBuilder()
                    .encodedPath(original.url.encodedPath)
                    .build()
                chain.proceed(original.newBuilder().url(local).build())
                    .newBuilder().request(original).build()
            }.build()

            val workerWithCover = DownloadWorker(
                okHttpClient = okHttpClient,
                downloadDao = appDatabase.downloadDao(),
                tempFileManager = tempFileManager,
                mediaStoreManager = mediaStoreManager,
                muxingEngine = DefaultMuxingEngine(),
                ephemeralHeaders = ephemeralHeaders,
                coverArtFetcher = com.mediasaver.core.download.metadata.CoverArtFetcher(coverClient)
            )

            appDatabase.downloadDao().insert(
                com.mediasaver.core.database.entity.DownloadEntity(
                    id = "tag-dl-1",
                    url = audioUrl,
                    platform = "YOUTUBE",
                    mediaId = "vidTag#audio",
                    title = "04 - Fiqh Lesson",
                    status = "QUEUED"
                )
            )

            workerWithCover.executeDownload(
                downloadId = "tag-dl-1",
                targetFileName = "04 - Fiqh Lesson.m4a",
                platform = PlatformId.YOUTUBE,
                videoStream = StreamInfo(
                    url = audioUrl,
                    quality = "128 kbps",
                    format = "m4a",
                    mimeType = "audio/mp4",
                    isAudioOnly = true,
                    fileSize = rawM4aBytes.size.toLong()
                ),
                subfolder = "Fiqh Series",
                audioMetadata = com.mediasaver.core.model.AudioMetadata(
                    title = "04 - Fiqh Lesson",
                    artist = "Ustadh Salim",
                    album = "Fiqh Series",
                    trackNumber = 4,
                    totalTracks = 20,
                    coverArtUrl = "https://i.ytimg.com/vi/vidTag/hqdefault.jpg"
                )
            ).getOrThrow()

            val tags = com.mediasaver.core.download.metadata.Mp4MetadataTagger.readTags(sinkFile)
            assertEquals("04 - Fiqh Lesson", tags.title)
            assertEquals("Ustadh Salim", tags.artist)
            assertEquals("Fiqh Series", tags.album)
            assertEquals(4, tags.trackNumber)
            assertEquals(20, tags.totalTracks)
            assertEquals(com.mediasaver.core.download.metadata.Mp4MetadataTagger.DEFAULT_ENCODER, tags.encoder)
            org.junit.Assert.assertArrayEquals(jpegCover, tags.coverArt?.bytes)

            val cv = requireNotNull(capturedValues)
            assertEquals("04 - Fiqh Lesson", cv.getAsString(MediaStore.Audio.AudioColumns.TITLE))
            assertEquals("Ustadh Salim", cv.getAsString(MediaStore.Audio.AudioColumns.ARTIST))
            assertEquals("Fiqh Series", cv.getAsString(MediaStore.Audio.AudioColumns.ALBUM))
            assertEquals(4, cv.getAsInteger(MediaStore.Audio.AudioColumns.TRACK))
        } finally {
            coverServer.shutdown()
            sinkFile.delete()
        }
    }
}
