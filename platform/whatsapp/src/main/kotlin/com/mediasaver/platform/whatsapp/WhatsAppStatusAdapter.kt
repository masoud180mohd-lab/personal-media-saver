package com.mediasaver.platform.whatsapp

import com.mediasaver.core.common.PlatformDetector
import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.domain.repository.StatusRepository
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.WhatsAppStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WhatsApp Status Adapter for Personal Media Saver.
 * This platform does not use network extraction; it uses local file access through Storage Access Framework (SAF).
 *
 * Responsibilities:
 * - Expose whether SAF access has been granted (for normal WhatsApp and WhatsApp Business)
 * - Enumerate status files from the granted directory
 * - Identify images and videos by extension/MIME type
 * - Produce WhatsApp status domain models
 * - Detect duplicates by file hash or filename + size + lastModified
 * - Copy selected statuses to app-managed MediaStore location (Pictures/MediaSaver/WhatsApp or Movies/MediaSaver/WhatsApp)
 */
@Singleton
open class WhatsAppStatusAdapter @Inject constructor(
    val statusRepository: StatusRepository
) : PlatformAdapter {

    override val platformId: PlatformId = PlatformId.WHATSAPP
    override val platformName: String = "WhatsApp Status"

    override fun canHandle(url: String): Boolean {
        if (url.startsWith("whatsapp://", ignoreCase = true)) return true
        if (url.contains("com.whatsapp", ignoreCase = true)) return true
        return PlatformDetector.detect(url) == PlatformId.WHATSAPP
    }

    /**
     * Checks if SAF access has been granted for the requested directory.
     * @param isBusiness true for WhatsApp Business, false for normal WhatsApp
     */
    suspend fun isAccessGranted(isBusiness: Boolean = false): Boolean {
        return statusRepository.isAccessGranted(isBusiness)
    }

    /**
     * Enumerates status files from the granted SAF directory,
     * identifying media types, sorting by lastModified descending,
     * and enriching with database duplicate/saved flags.
     */
    suspend fun enumerateStatuses(isBusiness: Boolean = false): Result<List<WhatsAppStatus>> {
        return statusRepository.scanStatuses(isBusiness)
    }

    /**
     * Copies selected status media to MediaStore:
     * - Pictures/MediaSaver/WhatsApp/ for images
     * - Movies/MediaSaver/WhatsApp/ for videos
     * Marks entity as saved in database and stores MediaStore URI.
     * Skips duplicates unless [overwrite] is true.
     */
    suspend fun saveStatus(status: WhatsAppStatus, overwrite: Boolean = false): Result<String> {
        return statusRepository.saveStatus(status, overwrite)
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val mediaId = "wa_${url.hashCode()}"
        Result.success(
            MediaInfo(
                id = mediaId,
                platform = PlatformId.WHATSAPP,
                title = "WhatsApp Status",
                description = "Local ephemeral media from WhatsApp Statuses",
                thumbnailUrl = null,
                durationSeconds = null,
                sourceUrl = url
            )
        )
    }

    override suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>> = withContext(Dispatchers.IO) {
        val stream = StreamInfo(
            url = mediaId,
            quality = "Original",
            format = "mp4",
            mimeType = "video/mp4",
            isVideoOnly = false,
            isAudioOnly = false,
            fileSize = null,
            bitrate = null,
            codec = null
        )
        Result.success(listOf(stream))
    }

    override suspend fun healthCheck(): AdapterStatus = withContext(Dispatchers.IO) {
        val hasStandard = statusRepository.isAccessGranted(isBusiness = false)
        val hasBusiness = statusRepository.isAccessGranted(isBusiness = true)

        if (hasStandard || hasBusiness) {
            AdapterStatus.OPERATIONAL
        } else {
            AdapterStatus.DEGRADED
        }
    }
}
