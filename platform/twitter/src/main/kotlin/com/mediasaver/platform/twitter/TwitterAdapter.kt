package com.mediasaver.platform.twitter

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
class TwitterAdapter @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val twitterExtractor: TwitterExtractor
) : PlatformAdapter {
    override val platformId = PlatformId.TWITTER
    override val platformName = "X (Twitter)"
    private val http = okHttpClient.twitterClient(setOf(SYNDICATION_HOST))

    private data class Cached(val time: Long, val media: TwitterMedia)
    private val cache = LinkedHashMap<String, Cached>(24, 0.75f, true)
    private val locks = Array(24) { Mutex() }

    override fun canHandle(url: String) = TwitterUrlParser.isTwitterUrl(url)

    private suspend fun details(id: String): Result<TwitterMedia> {
        if (!TwitterUrlParser.validId(id)) return Result.failure(AppError.ExtractionFailed(platformId))
        return locks[(id.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            val hit = synchronized(cache) {
                val now = System.nanoTime()
                cache.entries.removeAll { now - it.value.time >= 30_000_000_000L }
                cache[id]
            }
            if (hit != null) return@withLock Result.success(hit.media)

            twitterExtractor.extract(id).onSuccess { media ->
                synchronized(cache) {
                    cache[id] = Cached(System.nanoTime(), media)
                    if (cache.size > 24) cache.remove(cache.keys.first())
                }
            }
        }
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
        val canonical = try {
            TwitterUrlParser.resolveCanonicalUrl(url, okHttpClient)
        } catch (e: IOException) {
            return Result.failure(AppError.NetworkError(e))
        } ?: return Result.failure(AppError.ExtractionFailed(platformId))

        val id = canonical.substringAfterLast('/')
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
        http.readTwitter(syndicationRequest("https://$SYNDICATION_HOST").head().build()) {
            if (it.isSuccessful) AdapterStatus.OPERATIONAL else AdapterStatus.DEGRADED
        }
    } catch (_: IOException) {
        AdapterStatus.BROKEN
    }
}
