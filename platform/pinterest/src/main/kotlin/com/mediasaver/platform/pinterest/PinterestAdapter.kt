package com.mediasaver.platform.pinterest

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
class PinterestAdapter @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val pinterestExtractor: PinterestExtractor
) : PlatformAdapter {
    override val platformId = PlatformId.PINTEREST
    override val platformName = "Pinterest"
    private val http = okHttpClient.pinClient()

    private data class Cached(val time: Long, val media: PinterestMedia)
    private val cache = LinkedHashMap<String, Cached>(24, 0.75f, true)
    private val locks = Array(24) { Mutex() }

    override fun canHandle(url: String) = PinterestUrlParser.isPinterestUrl(url)

    private suspend fun details(id: String): Result<PinterestMedia> {
        if (!PinterestUrlParser.validId(id)) return Result.failure(AppError.ExtractionFailed(platformId))
        return locks[(id.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val hit = synchronized(cache) {
                val now = System.nanoTime()
                cache.entries.removeAll { now - it.value.time >= 30_000_000_000L }
                cache[id]
            }
            if (hit != null) return@withLock Result.success(hit.media)

            pinterestExtractor.extract(id).onSuccess { media ->
                synchronized(cache) {
                    cache[id] = Cached(System.nanoTime(), media)
                    if (cache.size > 24) cache.remove(cache.keys.first())
                }
            }
        }
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
        val canonical = try {
            PinterestUrlParser.resolveCanonicalUrl(url, okHttpClient)
        } catch (e: IOException) {
            return Result.failure(AppError.NetworkError(e))
        } ?: return Result.failure(AppError.ExtractionFailed(platformId))

        val id = canonical.removeSuffix("/").substringAfterLast('/')
        return details(id).map { media ->
            MediaInfo(
                id = id,
                platform = platformId,
                title = media.title,
                description = media.author,
                author = media.author,
                thumbnailUrl = media.thumbnailUrl,
                durationSeconds = media.durationSeconds,
                sourceUrl = canonical
            )
        }
    }

    override suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>> =
        details(mediaId).map { it.streams }

    override suspend fun healthCheck(): AdapterStatus = try {
        http.readPin(pinRequest("https://www.pinterest.com/").head().build()) {
            if (it.isSuccessful && it.request.url.encodedPath == "/") AdapterStatus.OPERATIONAL
            else AdapterStatus.DEGRADED
        }
    } catch (_: IOException) {
        AdapterStatus.BROKEN
    }
}
