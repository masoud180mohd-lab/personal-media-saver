package com.mediasaver.platform.whatsapp

import com.mediasaver.core.common.PlatformDetector
import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.domain.repository.StatusRepository
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform adapter implementation for WhatsApp Status local content access.
 * Manages local file discovery via Storage Access Framework without network extraction.
 */
@Singleton
class WhatsAppAdapter @Inject constructor(
    val statusRepository: StatusRepository
) : PlatformAdapter {

    override val platformId: PlatformId = PlatformId.WHATSAPP
    override val platformName: String = "WhatsApp"

    override fun canHandle(url: String): Boolean {
        if (url.startsWith("whatsapp://", ignoreCase = true)) return true
        if (url.contains("com.whatsapp", ignoreCase = true)) return true
        return PlatformDetector.detect(url) == PlatformId.WHATSAPP
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
        // WhatsApp statuses are local files, direct stream points to local content URI
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
            // Not broken, but awaiting user folder permission grant
            AdapterStatus.DEGRADED
        }
    }
}
