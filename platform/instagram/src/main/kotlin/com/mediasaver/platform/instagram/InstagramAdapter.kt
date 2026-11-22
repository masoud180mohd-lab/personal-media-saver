package com.mediasaver.platform.instagram

import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import com.mediasaver.core.network.interceptor.PlatformWebDomain
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

    private data class Cached(val time: Long, val details: InstagramMediaDetails)
    private val cache = LinkedHashMap<Pair<String, Boolean>, Cached>()

    private suspend fun details(id: String, expectVideo: Boolean): Result<InstagramMediaDetails> {
        val key = id to expectVideo
        synchronized(cache) {
            cache.entries.removeAll { System.nanoTime() - it.value.time >= 30_000_000_000L }
            cache[key]?.let { return Result.success(it.details) }
        }
        return instagramExtractor.extract(id, expectVideo).onSuccess {
            synchronized(cache) {
                if (cache.size >= 24) cache.remove(cache.keys.first())
                cache[key] = Cached(System.nanoTime(), it)
            }
        }
    }

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

        val extractionResult = details(shortcode, InstagramUrlParser.isReel(url))
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

    override suspend fun resolveStreams(mediaId: String) = resolveStreams(mediaId, "")

    override suspend fun resolveStreams(mediaId: String, sourceUrl: String): Result<List<StreamInfo>> =
        details(mediaId, InstagramUrlParser.isReel(sourceUrl)).map { media ->
            fun stream(video: String?, image: String?, index: Int?): StreamInfo {
                val quality = when {
                    index != null -> "Slide ${index + 1}"
                    video != null -> "HD"
                    media.isDegradedPreview -> "Preview"
                    else -> "High Resolution"
                }
                return StreamInfo(url = video ?: requireNotNull(image), quality = quality,
                    format = if (video != null) "mp4" else "jpg",
                    mimeType = if (video != null) "video/mp4" else "image/jpeg",
                    mediaIndex = index, thumbnailUrl = image)
            }
            if (media.carouselItems.isEmpty()) listOf(stream(media.videoUrl, media.imageUrl, null))
            else media.carouselItems.map { stream(it.videoUrl, it.imageUrl, it.mediaIndex) }
        }

    override suspend fun healthCheck(): AdapterStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.instagram.com")
                .tag(PlatformWebDomain::class.java, PlatformWebDomain("instagram.com"))
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
