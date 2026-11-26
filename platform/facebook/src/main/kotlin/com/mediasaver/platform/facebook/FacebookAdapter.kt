package com.mediasaver.platform.facebook

import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FacebookAdapter @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val facebookExtractor: FacebookExtractor
) : PlatformAdapter {
    override val platformId = PlatformId.FACEBOOK
    override val platformName = "Facebook"
    private val client = okHttpClient.facebookPublicClient()

    private data class Cached(val time: Long, val video: FacebookVideo)
    private val cache = LinkedHashMap<String, Cached>(24, 0.75f, true)
    private val locks = Array(24) { Mutex() }

    override fun canHandle(url: String) = FacebookUrlParser.isFacebookUrl(url)

    private suspend fun details(id: String): Result<FacebookVideo> {
        if (!FacebookUrlParser.validId(id)) {
            return Result.failure(AppError.ExtractionFailed(platformId))
        }

        val lock = locks[(id.hashCode() and Int.MAX_VALUE) % locks.size]
        return lock.withLock {
            val hit = synchronized(cache) {
                cache[id]?.takeIf { System.nanoTime() - it.time < 30_000_000_000L }
            }
            if (hit != null) return@withLock Result.success(hit.video)

            facebookExtractor.extract(id).onSuccess { video ->
                synchronized(cache) {
                    cache[id] = Cached(System.nanoTime(), video)
                    if (cache.size > 24) cache.remove(cache.keys.first())
                }
            }
        }
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
        val canonical = try {
            FacebookUrlParser.resolveCanonicalUrl(url, okHttpClient)
        } catch (e: IOException) {
            return Result.failure(AppError.NetworkError(e))
        } ?: return Result.failure(AppError.ExtractionFailed(platformId))

        val id = FacebookUrlParser.extractVideoId(canonical)
            ?: return Result.failure(AppError.ExtractionFailed(platformId))

        return details(id).map { video ->
            MediaInfo(
                id = id,
                platform = platformId,
                title = video.title ?: "Facebook video",
                description = video.author,
                author = video.author?.takeIf { it.isNotBlank() },
                thumbnailUrl = video.thumbnailUrl,
                sourceUrl = canonical
            )
        }
    }

    // The interface's sourceUrl overload delegates here. A numeric ID is sufficient
    // to reconstruct the canonical watch URL even after the cache entry expires.
    override suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>> =
        details(mediaId).map { video ->
            fun stream(url: String, quality: String) = StreamInfo(
                url = url,
                quality = quality,
                format = "mp4",
                mimeType = "video/mp4",
                codec = "h264",
                thumbnailUrl = video.thumbnailUrl
            )

            listOfNotNull(
                video.hdUrl?.let { stream(it, "HD (MP4)") },
                video.sdUrl?.takeIf { it != video.hdUrl }?.let { stream(it, "SD (MP4)") }
            )
        }

    override suspend fun healthCheck(): AdapterStatus {
        val request = facebookRequest("https://www.facebook.com").head().build()
        return try {
            client.readFacebook(request) {
                if (it.isSuccessful && it.request.url.encodedPath == "/") AdapterStatus.OPERATIONAL
                else AdapterStatus.DEGRADED
            }
        } catch (_: IOException) {
            AdapterStatus.BROKEN
        }
    }
}
