package com.mediasaver.platform.instagram

import com.mediasaver.core.common.PlatformDetector
import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform adapter implementation for Instagram public media extraction.
 */
@Singleton
class InstagramAdapter @Inject constructor() : PlatformAdapter {

    override val platformId: PlatformId = PlatformId.INSTAGRAM
    override val platformName: String = "Instagram"

    override fun canHandle(url: String): Boolean {
        return PlatformDetector.detect(url) == PlatformId.INSTAGRAM
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
        val shortcode = extractShortcode(url)
        return Result.success(
            MediaInfo(
                id = shortcode,
                platform = PlatformId.INSTAGRAM,
                title = "Instagram Post ($shortcode)",
                description = "Public media from Instagram",
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

    private fun extractShortcode(url: String): String {
        return when {
            url.contains("/p/") -> url.substringAfter("/p/").substringBefore("/").substringBefore("?")
            url.contains("/reel/") -> url.substringAfter("/reel/").substringBefore("/").substringBefore("?")
            else -> "ig_${url.hashCode()}"
        }
    }
}
