package com.mediasaver.platform.tiktok

import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import com.mediasaver.core.network.interceptor.PlatformWebDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform adapter implementation for TikTok content extraction.
 * Supports public video links, photo slideshows, and short links (`vm.tiktok.com`, `vt.tiktok.com`),
 * resolves redirect chains up to 5 hops, and caches recent extractions in memory.
 */
@Singleton
class TikTokAdapter @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tikTokExtractor: TikTokExtractor
) : PlatformAdapter {

    private data class Cached(val timeNs: Long, val details: TikTokMediaDetails)

    private val cache = LinkedHashMap<String, Cached>()

    override val platformId: PlatformId = PlatformId.TIKTOK
    override val platformName: String = "TikTok"

    override fun canHandle(url: String): Boolean {
        return TikTokUrlParser.isTikTokUrl(url)
    }

    private suspend fun fetchAndCacheDetails(videoId: String, targetUrl: String): Result<TikTokMediaDetails> {
        synchronized(cache) {
            cache.entries.removeAll { System.nanoTime() - it.value.timeNs >= CACHE_TTL_NS }
            cache[videoId]?.let { return Result.success(it.details) }
        }
        return tikTokExtractor.extract(targetUrl, videoId).onSuccess { details ->
            synchronized(cache) {
                while (cache.size >= MAX_CACHE_ENTRIES) {
                    cache.remove(cache.keys.first())
                }
                cache[videoId] = Cached(System.nanoTime(), details)
            }
        }
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        val resolvedUrl = TikTokUrlParser.resolveCanonicalUrl(url, okHttpClient)
            ?: return@withContext Result.failure(AppError.ExtractionFailed(PlatformId.TIKTOK))
        val videoId = TikTokUrlParser.extractVideoId(resolvedUrl)
            ?: return@withContext Result.failure(AppError.ExtractionFailed(PlatformId.TIKTOK))
        val extractionResult = fetchAndCacheDetails(videoId, resolvedUrl)

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
                sourceUrl = details.sourceUrl
            )
        )
    }

    override suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>> =
        resolveStreams(mediaId, "")

    override suspend fun resolveStreams(mediaId: String, sourceUrl: String): Result<List<StreamInfo>> =
        withContext(Dispatchers.IO) {
            val videoId = TikTokUrlParser.extractVideoId(mediaId)
                ?: return@withContext Result.failure(AppError.MediaNotFound(mediaId))

            val targetUrl = synchronized(cache) {
                cache.entries.removeAll { System.nanoTime() - it.value.timeNs >= CACHE_TTL_NS }
                cache[videoId]?.details?.sourceUrl
            } ?: if (sourceUrl.isNotBlank() && TikTokUrlParser.isTikTokUrl(sourceUrl)) {
                TikTokUrlParser.resolveCanonicalUrl(sourceUrl, okHttpClient)
                    ?: TikTokUrlParser.buildCanonicalWatchUrl(videoId)
            } else {
                TikTokUrlParser.buildCanonicalWatchUrl(videoId)
            }

            val extractionResult = fetchAndCacheDetails(videoId, targetUrl)
            if (extractionResult.isFailure) {
                return@withContext Result.failure(
                    extractionResult.exceptionOrNull() ?: AppError.ExtractionFailed(PlatformId.TIKTOK)
                )
            }

            val details = extractionResult.getOrThrow()
            if (details.photoItems.isNotEmpty()) {
                val slides = details.photoItems.map { item ->
                    StreamInfo(
                        url = item.imageUrl,
                        quality = "Slide ${item.mediaIndex + 1}",
                        format = "jpg",
                        mimeType = "image/jpeg",
                        isVideoOnly = false,
                        isAudioOnly = false,
                        mediaIndex = item.mediaIndex,
                        thumbnailUrl = item.imageUrl
                    )
                }
                return@withContext Result.success(slides)
            }

            val streams = mutableListOf<StreamInfo>()
            val primaryVideo = details.videoUrl
            if (!primaryVideo.isNullOrBlank()) {
                val primaryLabel = if (details.isWatermarkFree) "HD (No Watermark)" else "HD"
                streams.add(
                    StreamInfo(
                        url = primaryVideo,
                        quality = primaryLabel,
                        format = "mp4",
                        mimeType = "video/mp4",
                        isVideoOnly = false,
                        isAudioOnly = false,
                        codec = "h264",
                        thumbnailUrl = details.thumbnailUrl
                    )
                )
            }

            val watermarked = details.watermarkedVideoUrl
            if (!watermarked.isNullOrBlank() && watermarked != primaryVideo) {
                streams.add(
                    StreamInfo(
                        url = watermarked,
                        quality = "SD (Watermarked)",
                        format = "mp4",
                        mimeType = "video/mp4",
                        isVideoOnly = false,
                        isAudioOnly = false,
                        codec = "h264",
                        thumbnailUrl = details.thumbnailUrl
                    )
                )
            }

            val audio = details.audioUrl
            if (!audio.isNullOrBlank()) {
                streams.add(
                    StreamInfo(
                        url = audio,
                        quality = "Audio Only",
                        format = "mp3",
                        mimeType = "audio/mpeg",
                        isVideoOnly = false,
                        isAudioOnly = true,
                        codec = "mp3",
                        thumbnailUrl = details.thumbnailUrl
                    )
                )
            }

            if (streams.isEmpty()) {
                return@withContext Result.failure(AppError.ExtractionFailed(PlatformId.TIKTOK))
            }

            Result.success(streams)
        }

    override suspend fun healthCheck(): AdapterStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.tiktok.com")
                .tag(PlatformWebDomain::class.java, PlatformWebDomain("tiktok.com"))
                .head()
                .header("User-Agent", TikTokExtractor.MOBILE_USER_AGENT)
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                when {
                    resp.code == 429 -> AdapterStatus.DEGRADED
                    resp.isSuccessful || resp.code in 300..399 -> {
                        val preview = resp.body?.string().orEmpty()
                        if ("SlardarWAF" in preview || "slardar_us_waf" in preview) {
                            AdapterStatus.DEGRADED
                        } else {
                            AdapterStatus.OPERATIONAL
                        }
                    }
                    else -> AdapterStatus.DEGRADED
                }
            }
        } catch (_: Exception) {
            AdapterStatus.BROKEN
        }
    }

    private companion object {
        const val CACHE_TTL_NS = 30_000_000_000L
        const val MAX_CACHE_ENTRIES = 24
    }
}
