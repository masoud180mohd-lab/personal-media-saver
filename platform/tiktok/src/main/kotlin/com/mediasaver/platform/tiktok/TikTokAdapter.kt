package com.mediasaver.platform.tiktok

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
import com.mediasaver.core.network.interceptor.PlatformWebDomain
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform adapter implementation for TikTok content extraction.
 * Supports public video links and short-links (vm.tiktok.com, vt.tiktok.com),
 * resolves redirect chains up to 5 hops, and extracts direct MP4 media streams.
 */
@Singleton
class TikTokAdapter @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tikTokExtractor: TikTokExtractor
) : PlatformAdapter {

    override val platformId: PlatformId = PlatformId.TIKTOK
    override val platformName: String = "TikTok"

    override fun canHandle(url: String): Boolean {
        return TikTokUrlParser.isTikTokUrl(url)
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val resolvedUrl = TikTokUrlParser.resolveCanonicalUrl(url, okHttpClient)
            ?: return@withContext Result.failure(AppError.ExtractionFailed(PlatformId.TIKTOK))
        val videoId = TikTokUrlParser.extractVideoId(resolvedUrl)
            ?: return@withContext Result.failure(AppError.ExtractionFailed(PlatformId.TIKTOK))
        val extractionResult = tikTokExtractor.extract(resolvedUrl, videoId)

        if (extractionResult.isFailure) {
            return@withContext Result.failure(
                extractionResult.exceptionOrNull() ?: AppError.ExtractionFailed(PlatformId.TIKTOK)
            )
        }

        val details = extractionResult.getOrThrow()
        val displayTitle = details.title?.take(100)
            ?: details.author?.let { "TikTok Video by @$it" }
            ?: "TikTok Video"

        Result.success(
            MediaInfo(
                id = details.id,
                platform = PlatformId.TIKTOK,
                title = displayTitle,
                description = details.title,
                thumbnailUrl = details.thumbnailUrl,
                durationSeconds = details.durationSeconds,
                sourceUrl = resolvedUrl
            )
        )
    }

    override suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>> = withContext(Dispatchers.IO) {
        val videoId = TikTokUrlParser.extractVideoId(mediaId)
            ?: return@withContext Result.failure(AppError.MediaNotFound(mediaId))
        val canonicalUrl = TikTokUrlParser.buildCanonicalWatchUrl(videoId)
        val extractionResult = tikTokExtractor.extract(canonicalUrl, videoId)
        if (extractionResult.isFailure) {
            return@withContext Result.failure(
                extractionResult.exceptionOrNull() ?: AppError.ExtractionFailed(PlatformId.TIKTOK)
            )
        }

        val details = extractionResult.getOrThrow()
        val streams = mutableListOf<StreamInfo>()

        // 1. Watermark-Free Video Stream (or Primary Video)
        val primaryLabel = if (details.isWatermarkFree) "HD (No Watermark)" else "HD"
        streams.add(
            StreamInfo(
                url = details.videoUrl,
                quality = primaryLabel,
                format = "mp4",
                mimeType = "video/mp4",
                isVideoOnly = false,
                isAudioOnly = false,
                fileSize = null,
                bitrate = null,
                codec = "h264"
            )
        )

        // 2. Watermarked fallback stream (if distinct)
        if (!details.watermarkedVideoUrl.isNullOrBlank() && details.watermarkedVideoUrl != details.videoUrl) {
            streams.add(
                StreamInfo(
                    url = details.watermarkedVideoUrl,
                    quality = "SD (Watermarked)",
                    format = "mp4",
                    mimeType = "video/mp4",
                    isVideoOnly = false,
                    isAudioOnly = false,
                    fileSize = null,
                    bitrate = null,
                    codec = "h264"
                )
            )
        }

        // 3. Audio stream (MP3)
        if (!details.audioUrl.isNullOrBlank()) {
            streams.add(
                StreamInfo(
                    url = details.audioUrl,
                    quality = "Audio Only",
                    format = "mp3",
                    mimeType = "audio/mpeg",
                    isVideoOnly = false,
                    isAudioOnly = true,
                    fileSize = null,
                    bitrate = null,
                    codec = "mp3"
                )
            )
        }

        Result.success(streams)
    }

    override suspend fun healthCheck(): AdapterStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.tiktok.com")
                .tag(PlatformWebDomain::class.java, PlatformWebDomain("tiktok.com"))
                .head()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                when {
                    resp.isSuccessful || resp.code in 300..399 -> AdapterStatus.OPERATIONAL
                    resp.code == 429 -> AdapterStatus.DEGRADED
                    else -> AdapterStatus.DEGRADED
                }
            }
        } catch (_: Exception) {
            AdapterStatus.BROKEN
        }
    }
}
