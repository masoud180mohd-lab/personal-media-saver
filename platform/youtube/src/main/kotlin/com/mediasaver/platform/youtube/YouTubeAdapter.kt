package com.mediasaver.platform.youtube

import com.mediasaver.core.common.PlatformDetector
import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform adapter implementation for YouTube content extraction.
 */
@Singleton
class YouTubeAdapter @Inject constructor() : PlatformAdapter {

    override val platformId: PlatformId = PlatformId.YOUTUBE
    override val platformName: String = "YouTube"

    override fun canHandle(url: String): Boolean {
        return PlatformDetector.detect(url) == PlatformId.YOUTUBE
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
        val mediaId = extractVideoId(url)
        return Result.success(
            MediaInfo(
                id = mediaId,
                platform = PlatformId.YOUTUBE,
                title = "YouTube Media ($mediaId)",
                description = "Placeholder media extracted from YouTube",
                thumbnailUrl = "https://img.youtube.com/vi/$mediaId/hqdefault.jpg",
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
            url.contains("watch?v=") -> url.substringAfter("watch?v=").substringBefore("&").substringBefore("#")
            url.contains("youtu.be/") -> url.substringAfter("youtu.be/").substringBefore("?").substringBefore("#")
            url.contains("shorts/") -> url.substringAfter("shorts/").substringBefore("?").substringBefore("#")
            url.contains("live/") -> url.substringAfter("live/").substringBefore("?").substringBefore("#")
            else -> "yt_${url.hashCode()}"
        }
    }
}
