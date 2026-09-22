package com.mediasaver.platform.instagram

import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
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
    okHttpClient: OkHttpClient,
    private val instagramExtractor: InstagramExtractor
) : PlatformAdapter {
    companion object {
        private const val TAG = "MediaSaver_IgAdapter"
    }

    private val httpClient = okHttpClient.newBuilder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override val platformId: PlatformId = PlatformId.INSTAGRAM
    override val platformName: String = "Instagram"

    override fun canHandle(url: String): Boolean {
        return InstagramUrlParser.isInstagramUrl(url)
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val shortcode = InstagramUrlParser.extractShortcode(url)
            ?: run {
                Log.w(TAG, "Failed to extract shortcode from url: $url")
                return@withContext Result.failure(AppError.InvalidUrl(url))
            }

        val extractionResult = instagramExtractor.extract(shortcode)
        if (extractionResult.isFailure) {
            val ex = extractionResult.exceptionOrNull()
            Log.e(TAG, "extractMediaInfo failed for shortcode $shortcode", ex)
            return@withContext Result.failure(
                ex ?: AppError.ExtractionFailed(PlatformId.INSTAGRAM)
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
            val ex = extractionResult.exceptionOrNull()
            Log.e(TAG, "resolveStreams failed for mediaId $mediaId", ex)
            return@withContext Result.failure(
                ex ?: AppError.ExtractionFailed(PlatformId.INSTAGRAM)
            )
        }

        val details = extractionResult.getOrThrow()
        val streams = mutableListOf<StreamInfo>()

        if (details.carouselItems.isNotEmpty()) {
            details.carouselItems.forEachIndexed { index, item ->
                if (item.isVideo && !item.videoUrl.isNullOrBlank()) {
                    streams.add(
                        StreamInfo(
                            url = item.videoUrl,
                            quality = "Slide ${index + 1}",
                            format = "mp4",
                            mimeType = "video/mp4",
                            isVideoOnly = false,
                            isAudioOnly = false,
                            fileSize = null,
                            bitrate = null,
                            codec = "h264"
                        )
                    )
                } else if (!item.imageUrl.isNullOrBlank()) {
                    streams.add(
                        StreamInfo(
                            url = item.imageUrl,
                            quality = "Slide ${index + 1}",
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
            }
        } else {
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
        }

        if (streams.isEmpty()) {
            Log.w(TAG, "No streams resolved for mediaId $mediaId")
            return@withContext Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        Result.success(streams)
    }

    override suspend fun healthCheck(): AdapterStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.instagram.com")
                .head()
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { res ->
                val finalUrl = res.request.url.toString()
                if (finalUrl.contains("login") || finalUrl.contains("challenge")) {
                    Log.w(TAG, "Health check redirected to login/challenge (URL: $finalUrl)")
                    return@withContext AdapterStatus.DEGRADED
                }

                when (res.code) {
                    in 200..399 -> AdapterStatus.OPERATIONAL
                    429 -> {
                        Log.w(TAG, "Health check returned 429 Too Many Requests")
                        AdapterStatus.DEGRADED
                    }
                    else -> {
                        Log.w(TAG, "Health check returned unexpected code: ${res.code}")
                        AdapterStatus.DEGRADED
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed with exception", e)
            AdapterStatus.BROKEN
        }
    }
}
