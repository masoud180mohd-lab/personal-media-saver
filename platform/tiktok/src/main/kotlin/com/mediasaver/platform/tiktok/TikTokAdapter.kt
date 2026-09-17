package com.mediasaver.platform.tiktok

import com.mediasaver.core.common.PlatformDetector
import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform adapter implementation for TikTok content extraction.
 */
@Singleton
class TikTokAdapter @Inject constructor() : PlatformAdapter {

    override val platformId: PlatformId = PlatformId.TIKTOK
    override val platformName: String = "TikTok"

    override fun canHandle(url: String): Boolean {
        return PlatformDetector.detect(url) == PlatformId.TIKTOK
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
        val mediaId = extractVideoId(url)
        return Result.success(
            MediaInfo(
                id = mediaId,
                platform = PlatformId.TIKTOK,
                title = "TikTok Video ($mediaId)",
                description = "Placeholder media extracted from TikTok",
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

    private fun extractVideoId(url: String): String {
        return when {
            url.contains("/video/") -> url.substringAfter("/video/").substringBefore("?").substringBefore("#").trim('/')
            else -> "tiktok_${url.hashCode()}"
        }
    }
}
