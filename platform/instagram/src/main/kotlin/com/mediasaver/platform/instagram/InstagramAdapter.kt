package com.mediasaver.platform.instagram

import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform adapter implementation for Instagram public media extraction.
 * Supports public posts, reels, and IGTV videos/photos.
 * Zero-telemetry, zero stored credentials, and clean domain error mapping.
 */
@Singleton
class InstagramAdapter @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val instagramExtractor: InstagramExtractor
) : PlatformAdapter {

    override val platformId: PlatformId = PlatformId.INSTAGRAM
    override val platformName: String = "Instagram"

    override fun canHandle(url: String): Boolean {
        return InstagramUrlParser.isInstagramUrl(url)
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val shortcode = InstagramUrlParser.extractShortcode(url)
            ?: return@withContext Result.failure(AppError.InvalidUrl(url))

        val extractionResult = instagramExtractor.extract(shortcode)
        if (extractionResult.isFailure) {
            return@withContext Result.failure(
                extractionResult.exceptionOrNull() ?: AppError.ExtractionFailed(PlatformId.INSTAGRAM)
            )
        }

        val details = extractionResult.getOrThrow()
        val canonicalUrl = if (InstagramUrlParser.isReel(url)) {
            InstagramUrlParser.buildCanonicalReelUrl(shortcode)
        } else {
            InstagramUrlParser.buildCanonicalPostUrl(shortcode)
        }

        Result.success(
            MediaInfo(
                id = details.id,
                platform = PlatformId.INSTAGRAM,
                title = details.title,
                description = if (details.author != null) "Post by @${details.author}" else details.title,
                thumbnailUrl = details.thumbnailUrl,
                durationSeconds = details.durationSeconds,
                sourceUrl = canonicalUrl
            )
        )
    }

    override suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>> = withContext(Dispatchers.IO) {
        val extractionResult = instagramExtractor.extract(mediaId)
        if (extractionResult.isFailure) {
            return@withContext Result.failure(
                extractionResult.exceptionOrNull() ?: AppError.ExtractionFailed(PlatformId.INSTAGRAM)
            )
        }

        val details = extractionResult.getOrThrow()
        val streams = mutableListOf<StreamInfo>()

        if (details.isVideo && !details.videoUrl.isNullOrBlank()) {
            streams.add(
                StreamInfo(
                    url = details.videoUrl,
                    quality = "HD",
                    format = "mp4",
                    mimeType = "video/mp4",
                    isVideoOnly = false,
                    isAudioOnly = false,
                    fileSize = null,
                    bitrate = null,
                    codec = "h264"
                )
            )
        } else if (!details.imageUrl.isNullOrBlank()) {
            streams.add(
                StreamInfo(
                    url = details.imageUrl,
                    quality = "High Resolution",
                    format = "jpg",
                    mimeType = "image/jpeg",
                    isVideoOnly = false,
                    isAudioOnly = false,
                    fileSize = null,
                    bitrate = null,
                    codec = null
                )
            )
        }

        if (streams.isEmpty()) {
            return@withContext Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        Result.success(streams)
    }

    override suspend fun healthCheck(): AdapterStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.instagram.com")
                .head()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                when (res.code) {
                    in 200..399 -> AdapterStatus.OPERATIONAL
                    429 -> AdapterStatus.DEGRADED
                    else -> AdapterStatus.DEGRADED
                }
            }
        } catch (e: Exception) {
            AdapterStatus.BROKEN
        }
    }
}
