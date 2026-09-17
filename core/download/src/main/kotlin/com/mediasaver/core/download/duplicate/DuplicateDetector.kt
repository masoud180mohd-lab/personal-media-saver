package com.mediasaver.core.download.duplicate

import com.mediasaver.core.common.HashUtils
import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.database.entity.DownloadEntity
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class DuplicateReason {
    URL_AND_PLATFORM_MATCH,
    MEDIA_ID_MATCH,
    CONTENT_HASH_MATCH
}

data class DuplicateCheckResult(
    val isDuplicate: Boolean,
    val existingTask: DownloadTask? = null,
    val duplicateReason: DuplicateReason? = null
)

/**
 * Checks for existing completed or in-progress downloads to prevent duplicate file transfers.
 */
@Singleton
class DuplicateDetector @Inject constructor(
    private val downloadDao: DownloadDao
) {

    /**
     * Pre-download duplicate check against registered URL, platform, and mediaId records.
     */
    suspend fun checkBeforeDownload(
        url: String,
        platform: PlatformId,
        mediaId: String
    ): DuplicateCheckResult {
        val existingByUrl = downloadDao.findByUrlAndPlatform(url, platform.name)
        if (existingByUrl != null && (existingByUrl.status == "COMPLETED" || existingByUrl.status == "DOWNLOADING" || existingByUrl.status == "QUEUED" || existingByUrl.status == "MUXING")) {
            return DuplicateCheckResult(
                isDuplicate = true,
                existingTask = existingByUrl.toDomain(),
                duplicateReason = DuplicateReason.URL_AND_PLATFORM_MATCH
            )
        }

        if (mediaId.isNotBlank()) {
            val existingByMedia = downloadDao.findByMediaIdAndPlatform(mediaId, platform.name)
            if (existingByMedia != null && (existingByMedia.status == "COMPLETED" || existingByMedia.status == "DOWNLOADING" || existingByMedia.status == "QUEUED" || existingByMedia.status == "MUXING")) {
                return DuplicateCheckResult(
                    isDuplicate = true,
                    existingTask = existingByMedia.toDomain(),
                    duplicateReason = DuplicateReason.MEDIA_ID_MATCH
                )
            }
        }

        return DuplicateCheckResult(isDuplicate = false)
    }

    /**
     * Post-download integrity and duplicate check using SHA-256 hash.
     */
    suspend fun checkAfterDownload(file: File): DuplicateCheckResult {
        if (!file.exists()) return DuplicateCheckResult(isDuplicate = false)

        val hash = HashUtils.sha256(file)
        val existing = downloadDao.findByFileHash(hash)
        if (existing != null) {
            return DuplicateCheckResult(
                isDuplicate = true,
                existingTask = existing.toDomain(),
                duplicateReason = DuplicateReason.CONTENT_HASH_MATCH
            )
        }

        return DuplicateCheckResult(isDuplicate = false)
    }

    private fun DownloadEntity.toDomain(): DownloadTask {
        return DownloadTask(
            id = id,
            url = url,
            platform = PlatformId.fromString(platform),
            mediaId = mediaId,
            title = title,
            thumbnailUrl = thumbnailUrl,
            status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
            selectedQuality = selectedQuality,
            selectedFormat = selectedFormat,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            filePath = filePath,
            tempFilePath = tempFilePath,
            mimeType = mimeType,
            fileHash = fileHash,
            retryCount = retryCount,
            createdAt = createdAt,
            updatedAt = updatedAt,
            completedAt = completedAt,
            durationSeconds = durationSeconds
        )
    }
}
