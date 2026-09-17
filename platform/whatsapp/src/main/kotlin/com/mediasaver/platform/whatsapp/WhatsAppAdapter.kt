package com.mediasaver.platform.whatsapp

import com.mediasaver.core.common.PlatformDetector
import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform adapter implementation for WhatsApp local status access.
 */
@Singleton
class WhatsAppAdapter @Inject constructor() : PlatformAdapter {

    override val platformId: PlatformId = PlatformId.WHATSAPP
    override val platformName: String = "WhatsApp"

    override fun canHandle(url: String): Boolean {
        return PlatformDetector.detect(url) == PlatformId.WHATSAPP
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
        val mediaId = "wa_${url.hashCode()}"
        return Result.success(
            MediaInfo(
                id = mediaId,
                platform = PlatformId.WHATSAPP,
                title = "WhatsApp Status",
                description = "Local media status from WhatsApp",
                thumbnailUrl = null,
                durationSeconds = null,
                sourceUrl = url
            )
        )
    }

    override suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>> {
        return Result.success(emptyList())
    }

    override suspend fun healthCheck(): AdapterStatus {
        return AdapterStatus.OPERATIONAL
    }
}
