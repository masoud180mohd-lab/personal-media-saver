package com.mediasaver.core.download.duplicate

import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.database.entity.DownloadEntity
import com.mediasaver.core.model.PlatformId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DuplicateDetectorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeDao: FakeTestDownloadDao
    private lateinit var duplicateDetector: DuplicateDetector

    @Before
    fun setUp() {
        fakeDao = FakeTestDownloadDao()
        duplicateDetector = DuplicateDetector(fakeDao)
    }

    @Test
    fun `checkBeforeDownload detects duplicate when url and platform match completed task`() = runBlocking {
        fakeDao.items.add(
            createTestEntity(
                id = "task-1",
                url = "https://youtube.com/watch?v=abc",
                platform = "YOUTUBE",
                mediaId = "abc",
                status = "COMPLETED"
            )
        )

        val result = duplicateDetector.checkBeforeDownload(
            url = "https://youtube.com/watch?v=abc",
            platform = PlatformId.YOUTUBE,
            mediaId = "abc"
        )

        assertTrue(result.isDuplicate)
        assertEquals(DuplicateReason.URL_AND_PLATFORM_MATCH, result.duplicateReason)
        assertEquals("task-1", result.existingTask?.id)
    }

    @Test
    fun `checkBeforeDownload detects duplicate when mediaId and platform match active task`() = runBlocking {
        fakeDao.items.add(
            createTestEntity(
                id = "task-2",
                url = "https://youtu.be/short-link",
                platform = "YOUTUBE",
                mediaId = "vid-999",
                status = "DOWNLOADING"
            )
        )

        val result = duplicateDetector.checkBeforeDownload(
            url = "https://youtube.com/watch?v=different_url_same_id",
            platform = PlatformId.YOUTUBE,
            mediaId = "vid-999"
        )

        assertTrue(result.isDuplicate)
        assertEquals(DuplicateReason.MEDIA_ID_MATCH, result.duplicateReason)
        assertEquals("task-2", result.existingTask?.id)
    }

    @Test
    fun `checkBeforeDownload returns false when no matching record exists`() = runBlocking {
        val result = duplicateDetector.checkBeforeDownload(
            url = "https://youtube.com/watch?v=new_video",
            platform = PlatformId.YOUTUBE,
            mediaId = "new_video"
        )

        assertFalse(result.isDuplicate)
    }

    @Test
    fun `checkAfterDownload detects duplicate when file content hash matches existing completed download`() = runBlocking {
        val sampleFile = tempFolder.newFile("test_media.mp4").apply {
            writeBytes("Sample video stream payload for hash testing".toByteArray())
        }

        val hash = com.mediasaver.core.common.HashUtils.sha256(sampleFile)

        fakeDao.items.add(
            createTestEntity(
                id = "task-hash-match",
                url = "https://tiktok.com/@user/video/111",
                platform = "TIKTOK",
                mediaId = "111",
                status = "COMPLETED",
                fileHash = hash
            )
        )

        val result = duplicateDetector.checkAfterDownload(sampleFile)

        assertTrue(result.isDuplicate)
        assertEquals(DuplicateReason.CONTENT_HASH_MATCH, result.duplicateReason)
        assertEquals("task-hash-match", result.existingTask?.id)
    }

    private fun createTestEntity(
        id: String,
        url: String,
        platform: String,
        mediaId: String,
        status: String,
        fileHash: String? = null
    ) = DownloadEntity(
        id = id,
        url = url,
        platform = platform,
        mediaId = mediaId,
        title = "Test Video",
        description = null,
        thumbnailUrl = null,
        thumbnailLocalPath = null,
        status = status,
        errorType = null,
        errorMessage = null,
        selectedQuality = "720p",
        selectedFormat = "mp4",
        totalBytes = 1024L,
        downloadedBytes = 1024L,
        filePath = "/storage/test.mp4",
        tempFilePath = null,
        mimeType = "video/mp4",
        fileHash = fileHash,
        retryCount = 0,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        completedAt = System.currentTimeMillis(),
        durationSeconds = 60
    )

    private class FakeTestDownloadDao : DownloadDao {
        val items = mutableListOf<DownloadEntity>()

        override suspend fun insert(download: DownloadEntity) { items.add(download) }
        override suspend fun insertAll(downloads: List<DownloadEntity>) { items.addAll(downloads) }
        override suspend fun update(download: DownloadEntity) {}
        override suspend fun upsert(download: DownloadEntity) {}
        override suspend fun getById(id: String): DownloadEntity? = items.firstOrNull { it.id == id }
        override fun observeById(id: String): Flow<DownloadEntity?> = emptyFlow()
        override fun observeAll(): Flow<List<DownloadEntity>> = emptyFlow()
        override suspend fun updateStatus(id: String, status: String, updatedAt: Long) {}
        override suspend fun updateProgress(id: String, downloadedBytes: Long, totalBytes: Long?, updatedAt: Long) {}
        override suspend fun markCompleted(id: String, filePath: String, fileHash: String?, completedAt: Long, updatedAt: Long) {}
        override suspend fun markFailed(id: String, errorType: String?, errorMessage: String?, updatedAt: Long) {}
        override suspend fun markCancelled(id: String, updatedAt: Long) {}
        override suspend fun deleteById(id: String) { items.removeAll { it.id == id } }
        override suspend fun delete(download: DownloadEntity) { items.remove(download) }
        override suspend fun clearHistory() {}
        override suspend fun deleteAll() { items.clear() }
        override suspend fun findByUrlAndPlatform(url: String, platform: String): DownloadEntity? {
            return items.firstOrNull { it.url == url && it.platform == platform }
        }
        override suspend fun findByMediaIdAndPlatform(mediaId: String, platform: String): DownloadEntity? {
            return items.firstOrNull { it.mediaId == mediaId && it.platform == platform }
        }
        override suspend fun findByFileHash(fileHash: String): DownloadEntity? {
            return items.firstOrNull { it.fileHash == fileHash && it.status == "COMPLETED" }
        }
        override suspend fun updateRetryCount(id: String, retryCount: Int, updatedAt: Long) {}
        override suspend fun findActiveDownloads(): List<DownloadEntity> = items.filter { it.status in listOf("QUEUED", "EXTRACTING", "DOWNLOADING", "MUXING") }
        override fun observeActiveDownloads(): Flow<List<DownloadEntity>> = emptyFlow()
    }
}
