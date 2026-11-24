package com.mediasaver.platform.youtube

import android.util.Log
import com.mediasaver.core.common.AppLogger
import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.PlaylistItem
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import com.mediasaver.platform.youtube.downloader.NewPipeDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo as NewPipePlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo as NewPipeStreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform adapter implementation for YouTube utilizing NewPipe Extractor.
 * Guarantees zero credentials, zero tracking, Scoped Storage compliance,
 * and seamless progressive/adaptive DASH stream extraction.
 */
@Singleton
class YouTubeAdapter @Inject constructor(
    private val newPipeDownloader: NewPipeDownloader,
    private val streamMapper: YouTubeStreamMapper
) : PlatformAdapter {

    companion object {
        private const val TAG = "YouTubeAdapter"
        internal const val MAX_PLAYLIST_ITEMS = 500
        internal const val MAX_PLAYLIST_PAGES = 10

        internal suspend fun <P> collectPaginatedPlaylistItems(
            initialItems: List<PlaylistItem>,
            initialNextPage: P?,
            hasNextPage: (P?) -> Boolean,
            fetchNextPage: suspend (P) -> Pair<List<PlaylistItem>, P?>,
            maxItems: Int = MAX_PLAYLIST_ITEMS,
            maxPages: Int = MAX_PLAYLIST_PAGES
        ): List<PlaylistItem> {
            val collected = LinkedHashMap<String, PlaylistItem>()
            for (item in initialItems) {
                if (collected.size >= maxItems) break
                collected.putIfAbsent(item.videoId, item)
            }

            var nextPage = initialNextPage
            var pagesLoaded = 1
            while (nextPage != null && hasNextPage(nextPage) && pagesLoaded < maxPages && collected.size < maxItems) {
                currentCoroutineContext().ensureActive()
                val currentPage = nextPage
                val (pageItems, followingPage) = try {
                    fetchNextPage(currentPage)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLogger.w("MediaSaver:YouTube", "Playlist pagination stopped at page ${pagesLoaded + 1}: ${e.javaClass.simpleName}: ${e.message}")
                    break
                }

                var addedOnPage = 0
                for (item in pageItems) {
                    if (collected.size >= maxItems) break
                    if (collected.putIfAbsent(item.videoId, item) == null) {
                        addedOnPage++
                    }
                }
                pagesLoaded++
                if (addedOnPage == 0 || followingPage == currentPage) {
                    break
                }
                nextPage = followingPage
            }
            return collected.values.toList()
        }
    }

    override val platformId: PlatformId = PlatformId.YOUTUBE
    override val platformName: String = "YouTube"

    @Volatile
    private var isInitialized: Boolean = false

    @Synchronized
    private fun ensureInitialized() {
        if (!isInitialized) {
            Log.d(TAG, "Initializing NewPipe Extractor...")
            NewPipe.init(newPipeDownloader, Localization.DEFAULT, ContentCountry.DEFAULT)
            isInitialized = true
            Log.d(TAG, "NewPipe Extractor initialized successfully")
        }
    }

    override fun canHandle(url: String): Boolean {
        return YouTubeUrlParser.isYouTubeUrl(url)
    }

    override suspend fun extractMediaInfo(url: String): Result<MediaInfo> = withContext(Dispatchers.IO) {
        if (YouTubeUrlParser.isPlaylistOnlyUrl(url)) {
            return@withContext extractPlaylistInfo(url)
        }

        val videoId = YouTubeUrlParser.extractVideoId(url)
            ?: return@withContext Result.failure(AppError.InvalidUrl(url))
        val canonicalUrl = YouTubeUrlParser.buildCanonicalWatchUrl(videoId)

        Log.d(TAG, "extractMediaInfo: videoId=$videoId, url=$canonicalUrl")

        try {
            ensureInitialized()
            val info = NewPipeStreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)

            Log.d(TAG, "extractMediaInfo success: title='${info.name}', duration=${info.duration}")

            val bestThumbnail = info.thumbnails?.maxByOrNull { it.width ?: 0 }?.url
                ?: "https://img.youtube.com/vi/$videoId/hqdefault.jpg"

            Result.success(
                MediaInfo(
                    id = videoId,
                    platform = PlatformId.YOUTUBE,
                    title = info.name,
                    description = info.description?.content,
                    thumbnailUrl = bestThumbnail,
                    durationSeconds = info.duration.toInt(),
                    sourceUrl = canonicalUrl
                )
            )
        } catch (e: Throwable) {
            Log.e(TAG, "extractMediaInfo FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(mapError(e, url))
        }
    }

    private suspend fun extractPlaylistInfo(url: String): Result<MediaInfo> {
        val playlistId = YouTubeUrlParser.extractPlaylistId(url)
            ?: return Result.failure(AppError.InvalidUrl(url))
        val canonicalUrl = YouTubeUrlParser.buildCanonicalPlaylistUrl(playlistId)

        Log.d(TAG, "extractPlaylistInfo: playlistId=$playlistId, url=$canonicalUrl")

        return try {
            ensureInitialized()
            val extractor = ServiceList.YouTube.getPlaylistExtractor(canonicalUrl)
            extractor.fetchPage()
            val playlistInfo = NewPipePlaylistInfo.getInfo(extractor)
            val fallbackAuthor = playlistInfo.uploaderName
            val initialItems = playlistInfo.relatedItems
                .orEmpty()
                .mapNotNull { mapPlaylistItem(it, fallbackAuthor) }

            val items = collectPaginatedPlaylistItems(
                initialItems = initialItems,
                initialNextPage = if (playlistInfo.hasNextPage()) playlistInfo.nextPage else null,
                hasNextPage = { page -> page != null && Page.isValid(page) },
                fetchNextPage = { page ->
                    val more = extractor.getPage(page)
                    val mapped = more.items.orEmpty().mapNotNull { mapPlaylistItem(it, fallbackAuthor) }
                    mapped to if (more.hasNextPage()) more.nextPage else null
                }
            )

            if (items.isEmpty()) {
                return Result.failure(AppError.MediaNotFound(url))
            }

            val bestThumbnail = playlistInfo.thumbnails?.maxByOrNull { it.width ?: 0 }?.url
                ?: items.first().thumbnailUrl
            val totalDuration = items.sumOf { it.durationSeconds ?: 0 }.takeIf { it > 0 }
            val reportedTotal = playlistInfo.streamCount.takeIf { it > 0L }?.toInt()?.coerceAtLeast(items.size)

            Log.d(TAG, "extractPlaylistInfo success: title='${playlistInfo.name}', loaded=${items.size}, reportedTotal=$reportedTotal")

            Result.success(
                MediaInfo(
                    id = playlistId,
                    platform = PlatformId.YOUTUBE,
                    title = playlistInfo.name?.takeIf { it.isNotBlank() } ?: "YouTube Playlist (${items.size} videos)",
                    description = playlistInfo.uploaderName,
                    thumbnailUrl = bestThumbnail,
                    durationSeconds = totalDuration,
                    sourceUrl = canonicalUrl,
                    playlistItems = items,
                    totalPlaylistCount = reportedTotal
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "extractPlaylistInfo FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(mapError(e, url))
        }
    }

    private fun mapPlaylistItem(item: StreamInfoItem, fallbackAuthor: String?): PlaylistItem? {
        val itemVideoId = YouTubeUrlParser.extractVideoId(item.url.orEmpty()) ?: return null
        val itemUrl = YouTubeUrlParser.buildCanonicalWatchUrl(itemVideoId)
        val thumb = item.thumbnails?.maxByOrNull { it.width ?: 0 }?.url
            ?: "https://img.youtube.com/vi/$itemVideoId/hqdefault.jpg"
        return PlaylistItem(
            videoId = itemVideoId,
            title = item.name?.takeIf { it.isNotBlank() } ?: "YouTube Video ($itemVideoId)",
            author = item.uploaderName?.takeIf { it.isNotBlank() } ?: fallbackAuthor,
            thumbnailUrl = thumb,
            durationSeconds = item.duration.takeIf { it > 0 }?.toInt(),
            url = itemUrl
        )
    }

    override suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>> = withContext(Dispatchers.IO) {
        val canonicalUrl = YouTubeUrlParser.buildCanonicalWatchUrl(mediaId)

        Log.d(TAG, "resolveStreams: mediaId=$mediaId")

        try {
            ensureInitialized()
            val info = NewPipeStreamInfo.getInfo(ServiceList.YouTube, canonicalUrl)

            val progressive = info.videoStreams?.map { streamMapper.mapProgressiveStream(it) }.orEmpty()
            val adaptiveVideo = info.videoOnlyStreams?.map { streamMapper.mapAdaptiveVideoStream(it) }.orEmpty()
            val audio = info.audioStreams?.map { streamMapper.mapAudioStream(it) }.orEmpty()

            val allStreams = progressive + adaptiveVideo + audio
            Log.d(TAG, "resolveStreams: found ${progressive.size} progressive, ${adaptiveVideo.size} adaptive video, ${audio.size} audio")

            if (allStreams.isEmpty()) {
                return@withContext Result.failure(
                    AppError.ExtractionFailed(
                        platform = PlatformId.YOUTUBE,
                        cause = Exception("No playable streams returned by extractor for $mediaId")
                    )
                )
            }

            Result.success(allStreams)
        } catch (e: Throwable) {
            Log.e(TAG, "resolveStreams FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            Result.failure(mapError(e, canonicalUrl))
        }
    }

    override suspend fun healthCheck(): AdapterStatus = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            // Reference test video: YouTube's official first video ("Me at the zoo")
            val testUrl = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
            val info = NewPipeStreamInfo.getInfo(ServiceList.YouTube, testUrl)
            if (info.name.isNullOrBlank()) {
                AdapterStatus.DEGRADED
            } else {
                AdapterStatus.OPERATIONAL
            }
        } catch (_: ExtractionException) {
            AdapterStatus.BROKEN
        } catch (_: Exception) {
            AdapterStatus.DEGRADED
        }
    }

    private fun mapError(throwable: Throwable, url: String): AppError {
        return when (throwable) {
            is AppError -> throwable
            is ContentNotAvailableException -> AppError.MediaNotFound(url)
            is AgeRestrictedContentException -> AppError.ContentRestricted(
                reason = "Age-restricted YouTube content requires account sign-in, which is not supported."
            )
            is PrivateContentException -> AppError.ContentRestricted(
                reason = "This video is private and cannot be accessed."
            )
            is PaidContentException -> AppError.ContentRestricted(
                reason = "This video requires a paid membership or rental."
            )
            is GeographicRestrictionException -> AppError.ContentRestricted(
                reason = "This video is blocked in your geographical region."
            )
            is ParsingException,
            is ExtractionException -> AppError.ExtractionFailed(
                platform = PlatformId.YOUTUBE,
                cause = throwable
            )
            is SocketTimeoutException -> AppError.DownloadTimeout(url)
            is UnknownHostException,
            is IOException -> AppError.NetworkError(cause = throwable)
            else -> AppError.Unknown(cause = throwable)
        }
    }
}
