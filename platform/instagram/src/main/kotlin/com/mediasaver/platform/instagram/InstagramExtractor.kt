package com.mediasaver.platform.instagram

import android.util.Log
import com.mediasaver.core.common.AppLogger
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigInteger
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracted metadata and stream URLs for an Instagram post or reel.
 */
data class InstagramCarouselItem(
    val videoUrl: String?,
    val imageUrl: String?,
    val isVideo: Boolean
)

/**
 * Extracted metadata and stream URLs for an Instagram post or reel.
 */
data class InstagramMediaDetails(
    val id: String,
    val title: String?,
    val author: String?,
    val thumbnailUrl: String?,
    val videoUrl: String?,
    val imageUrl: String?,
    val isVideo: Boolean,
    val durationSeconds: Int? = null,
    val carouselItems: List<InstagramCarouselItem> = emptyList()
)

/**
 * Multi-tier extraction engine for Instagram posts and reels.
 *
 * Tier 0: Authenticated API using local encrypted session cookies.
 * Tier 1: Public Embed Page (/embed/captioned/)
 * Tier 2: Post/Reel HTML (OpenGraph & embedded JSON)
 * Tier 3: Web API query (?__a=1&__d=dis)
 *
 * Strictly respects zero-telemetry and zero-external-service architecture.
 */
@Singleton
class InstagramExtractor @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sessionManager: InstagramSessionManager
) {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
        private const val IG_APP_ID = "936619743392459"
        private const val TAG = "MediaSaver_IgAdapter"
    }

    /**
     * Extracts media information for a given shortcode.
     */
    suspend fun extract(shortcode: String): Result<InstagramMediaDetails> = withContext(Dispatchers.IO) {
        val cookies = sessionManager.getCookies()

        // --- Authenticated Flows (when session cookies are present) ---
        if (!cookies.isNullOrBlank()) {
            Log.d(TAG, "Authenticated mode: extracting for $shortcode")

            // 1. Authenticated Mobile API
            val authApiResult = extractFromAuthenticatedApi(shortcode, cookies)
            if (authApiResult.isSuccess) {
                Log.d(TAG, "Tier 0 (AuthApi) SUCCESS")
                return@withContext authApiResult
            }
            Log.w(TAG, "Tier 0 (AuthApi) failed: ${authApiResult.exceptionOrNull()?.message}")

            // 2. Web API with session cookies
            val webApiResult = extractFromWebApi(shortcode, cookies)
            if (webApiResult.isSuccess) {
                Log.d(TAG, "Tier 0 (WebApi with cookies) SUCCESS")
                return@withContext webApiResult
            }
            Log.w(TAG, "Tier 0 (WebApi with cookies) failed: ${webApiResult.exceptionOrNull()?.message}")

            // 3. Post HTML with session cookies
            val postHtmlResult = extractFromPostHtml(shortcode, cookies)
            if (postHtmlResult.isSuccess) {
                Log.d(TAG, "Tier 0 (PostHtml with cookies) SUCCESS")
                return@withContext postHtmlResult
            }
            Log.w(TAG, "Tier 0 (PostHtml with cookies) failed: ${postHtmlResult.exceptionOrNull()?.message}")

            // 4. Embed page with session cookies
            val embedResult = extractFromEmbedPage(shortcode, cookies)
            if (embedResult.isSuccess) {
                Log.d(TAG, "Tier 0 (Embed with cookies) SUCCESS")
                return@withContext embedResult
            }

            // If session might be expired, log it
            if (authApiResult.exceptionOrNull() is AppError.ContentRestricted) {
                Log.w(TAG, "Session appears expired or invalid")
            }

            return@withContext Result.failure(
                AppError.ContentRestricted(
                    "Instagram haikuweza kupakua video hii. Huenda session yako imeisha au post hii ni private. Tafadhali nenda kwenye Settings kisha u-login tena Instagram."
                )
            )
        }

        // --- Unauthenticated Flows (attempt public endpoints) ---
        Log.d(TAG, "Unauthenticated mode: attempting public tiers for $shortcode")

        // Tier 1: Public Embed Page
        val embedResult = extractFromEmbedPage(shortcode, null)
        if (embedResult.isSuccess) {
            Log.d(TAG, "Tier 1 (Public Embed) SUCCESS")
            return@withContext embedResult
        }

        // Tier 2: Public Post HTML
        val postHtmlResult = extractFromPostHtml(shortcode, null)
        if (postHtmlResult.isSuccess) {
            Log.d(TAG, "Tier 2 (Public HTML) SUCCESS")
            return@withContext postHtmlResult
        }

        // Tier 3: Public Web API
        val webApiResult = extractFromWebApi(shortcode, null)
        if (webApiResult.isSuccess) {
            Log.d(TAG, "Tier 3 (Public WebApi) SUCCESS")
            return@withContext webApiResult
        }

        // Unauthenticated extraction failed because Instagram blocks public scraping
        Log.w(TAG, "All unauthenticated tiers failed for $shortcode — login required")
        Result.failure(
            AppError.ContentRestricted(
                "Instagram inahitaji uwe umeingia (Login). Tafadhali fungua Settings kisha ubonyeze 'Login to Instagram' ili kuwezesha kupakua video na reels."
            )
        )
    }

    /**
     * Tier 0: Authenticated API using session cookies.
     */
    internal fun extractFromAuthenticatedApi(shortcode: String, cookies: String): Result<InstagramMediaDetails> {
        val csrfToken = Regex("csrftoken=([^;]+)").find(cookies)?.groupValues?.getOrNull(1) ?: ""
        val mediaId = shortcodeToMediaId(shortcode)
        val apiUrl = "https://www.instagram.com/api/v1/media/$mediaId/info/"

        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", cookies)
            .header("X-CSRFToken", csrfToken)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-IG-WWW-Claim", "0")
            .header("Accept", "*/*")
            .header("Referer", "https://www.instagram.com/p/$shortcode/")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (res.code == 404) {
                    return Result.failure(AppError.MediaNotFound(apiUrl))
                }
                if (res.code == 401 || res.code == 403) {
                    return Result.failure(AppError.ContentRestricted("Instagram session expired"))
                }

                val json = res.body?.string().orEmpty()
                Log.d(TAG, "AuthApi response code: ${res.code}, length: ${json.length}")

                if (json.contains("\"require_login\":true") || json.contains("\"message\":\"login_required\"")) {
                    return Result.failure(AppError.ContentRestricted("Instagram session expired"))
                }

                parseApiResponseJson(shortcode, json)
            }
        } catch (e: IOException) {
            Result.failure(AppError.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM, e))
        }
    }

    /**
     * Tier 1: Extracts media from the public/embed endpoint:
     * https://www.instagram.com/p/{shortcode}/embed/captioned/
     */
    internal fun extractFromEmbedPage(shortcode: String, cookies: String? = null): Result<InstagramMediaDetails> {
        val embedUrl = InstagramUrlParser.buildEmbedUrl(shortcode)
        val requestBuilder = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.instagram.com/")

        if (!cookies.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookies)
        }

        return try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { res ->
                if (res.code == 404) {
                    return Result.failure(AppError.MediaNotFound(embedUrl))
                }

                val finalUrl = res.request.url.toString()
                if (finalUrl.contains("/accounts/login/") || finalUrl.contains("/challenge/")) {
                    return Result.failure(AppError.ContentRestricted("Instagram requires login"))
                }

                val html = res.body?.string().orEmpty()
                parseEmbedHtml(shortcode, html)
            }
        } catch (e: IOException) {
            Result.failure(AppError.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM, e))
        }
    }

    /**
     * Parses embed page HTML.
     */
    internal fun parseEmbedHtml(shortcode: String, html: String): Result<InstagramMediaDetails> {
        if (html.isBlank()) {
            return Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        if (html.contains("Login &bull; Instagram") || html.contains("accounts/login") || html.contains("checkpoint_required")) {
            return Result.failure(AppError.ContentRestricted("Instagram requires login"))
        }

        // 1. Direct Video Tag or embedded video URL
        val videoUrlMatch = Regex("""<video[^>]+class="[^"]*EmbeddedMediaVideo[^"]*"[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<video[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""["']video_url["']\s*:\s*["']([^"']+)["']""").find(html)
            ?: Regex(""""url"\s*:\s*"(https?:\\/\\/[^"]+\.mp4[^"]*)"""").find(html)
            ?: Regex(""""url"\s*:\s*"(https?://[^"]+\.mp4[^"]*)"""").find(html)
            ?: Regex("""(https?:\\/\\/[^"'<>\s]+\.mp4[^"'<>\s]*)""").find(html)
            ?: Regex("""(https?://[^"'<>\s]+\.mp4[^"'<>\s]*)""").find(html)

        val videoUrl = videoUrlMatch?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }?.takeIf { !it.startsWith("data:") }

        // 2. Direct Image / Thumbnail Tag
        val imgMatch = Regex("""<img[^>]+class="[^"]*EmbeddedMediaImage[^"]*"[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""["']display_url["']\s*:\s*["']([^"']+)["']""").find(html)
            ?: Regex(""""url"\s*:\s*"(https?:\\/\\/[^"]+(?:scontent|cdninstagram)[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""").find(html)

        val imageUrl = imgMatch?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }?.takeIf { !it.startsWith("data:") }

        // 3. Caption / Title
        val captionMatch = Regex("""<div[^>]+class="[^"]*Caption[^"]*"[^>]*>(.*?)</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)
            ?: Regex("""<div[^>]+class="[^"]*CaptionText[^"]*"[^>]*>(.*?)</div>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)

        val rawCaption = captionMatch?.groupValues?.getOrNull(1)
        val title = rawCaption?.let { stripHtmlTags(it) }?.take(150)?.ifBlank { null }

        // 4. Author / Username
        val authorMatch = Regex("""<a[^>]+class="[^"]*Username[^"]*"[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<a[^>]+class="[^"]*Avatar[^"]*"[^>]+href="[/\\]*([^/"\\]+)""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""["']username["']\s*:\s*["']([^"']+)["']""").find(html)

        val author = authorMatch?.groupValues?.getOrNull(1)?.let { stripHtmlTags(it).trim() }?.ifBlank { null }

        val isVideo = !videoUrl.isNullOrBlank()
        if (!isVideo && imageUrl.isNullOrBlank()) {
            return Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        return Result.success(
            InstagramMediaDetails(
                id = shortcode,
                title = title ?: (if (isVideo) "Instagram Reel ($shortcode)" else "Instagram Post ($shortcode)"),
                author = author,
                thumbnailUrl = imageUrl ?: videoUrl,
                videoUrl = videoUrl,
                imageUrl = imageUrl,
                isVideo = isVideo,
                durationSeconds = null
            )
        )
    }

    /**
     * Tier 2: Canonical Post or Reel HTML page via OpenGraph tags.
     */
    internal fun extractFromPostHtml(shortcode: String, cookies: String? = null): Result<InstagramMediaDetails> {
        val postUrl = InstagramUrlParser.buildCanonicalPostUrl(shortcode)
        val requestBuilder = Request.Builder()
            .url(postUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")

        if (!cookies.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookies)
        }

        return try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { res ->
                if (res.code == 404) {
                    return Result.failure(AppError.MediaNotFound(postUrl))
                }

                val finalUrl = res.request.url.toString()
                if (finalUrl.contains("/accounts/login/")) {
                    return Result.failure(AppError.ContentRestricted("Instagram requires login"))
                }

                val html = res.body?.string().orEmpty()
                parsePostHtml(shortcode, html)
            }
        } catch (e: IOException) {
            Result.failure(AppError.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM, e))
        }
    }

    /**
     * Parses post HTML for OpenGraph meta tags, embedded scripts, and JSON-LD data.
     */
    internal fun parsePostHtml(shortcode: String, html: String): Result<InstagramMediaDetails> {
        if (html.isBlank()) {
            return Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        if (html.contains("Login &bull; Instagram") || html.contains("accounts/login")) {
            return Result.failure(AppError.ContentRestricted("Instagram requires login"))
        }

        // OpenGraph Video
        val ogVideo = Regex("""<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+property=["']og:video:secure_url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:video["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""["']video_url["']\s*:\s*["']([^"']+)["']""").find(html)
            ?: Regex(""""url"\s*:\s*"(https?:\\/\\/[^"]+\.mp4[^"]*)"""").find(html)
            ?: Regex(""""url"\s*:\s*"(https?://[^"]+\.mp4[^"]*)"""").find(html)
            ?: Regex("""(https?:\\/\\/[^"'<>\s]+\.mp4[^"'<>\s]*)""").find(html)
            ?: Regex("""(https?://[^"'<>\s]+\.mp4[^"'<>\s]*)""").find(html)

        val videoUrl = ogVideo?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }?.takeIf { !it.startsWith("data:") }

        // OpenGraph Image
        val ogImage = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""["']display_url["']\s*:\s*["']([^"']+)["']""").find(html)

        val imageUrl = ogImage?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }?.takeIf { !it.startsWith("data:") }

        // OpenGraph Title & Description
        val ogTitle = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
        val ogDesc = Regex("""<meta[^>]+property=["']og:description["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)

        val rawTitle = ogTitle?.groupValues?.getOrNull(1) ?: ogDesc?.groupValues?.getOrNull(1)
        val title = rawTitle?.let { stripHtmlTags(it) }?.take(150)?.ifBlank { null }

        val author = ogTitle?.groupValues?.getOrNull(1)?.let {
            if (it.contains("on Instagram:")) {
                unescapeHtmlEntities(it.substringBefore("on Instagram:").trim().removePrefix("@"))
            } else {
                null
            }
        }

        val isVideo = !videoUrl.isNullOrBlank()
        if (!isVideo && imageUrl.isNullOrBlank()) {
            return Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        val carouselItems = extractCarouselItems(html)
        if (carouselItems.isNotEmpty()) {
            AppLogger.d(TAG, "Layer 2 carousel scan used; carousel item count=${carouselItems.size}")
        } else {
            AppLogger.d(TAG, "OpenGraph layer used; carousel item count=0")
        }

        return Result.success(
            InstagramMediaDetails(
                id = shortcode,
                title = title ?: (if (isVideo) "Instagram Reel ($shortcode)" else "Instagram Post ($shortcode)"),
                author = author,
                thumbnailUrl = imageUrl ?: videoUrl,
                videoUrl = videoUrl,
                imageUrl = imageUrl,
                isVideo = isVideo,
                durationSeconds = null,
                carouselItems = carouselItems
            )
        )
    }

    /**
     * Scans the embedded carousel containers without requiring valid JSON. Instagram
     * frequently embeds escaped or partially hydrated JSON in the HTML response.
     */
    internal fun extractCarouselItems(html: String): List<InstagramCarouselItem> {
        val markers = listOf("carousel_media", "edge_sidecar_to_children", "shortcode_media")
        val regions = markers.mapNotNull { marker ->
            val markerIndex = html.indexOf(marker)
            if (markerIndex < 0) return@mapNotNull null
            val scriptEnd = html.indexOf("</script>", markerIndex).let { if (it < 0) html.length else it }
            html.substring(markerIndex, scriptEnd)
        }

        val itemPattern = Regex("""["'](display_url|video_url)["']\s*:\s*["']([^"']+)["']""")
        val items = mutableListOf<InstagramCarouselItem>()

        for (region in regions) {
            val signals = itemPattern.findAll(region).toList()
            val displaySignals = signals.filter { it.groupValues[1] == "display_url" }
            for ((index, display) in displaySignals.withIndex()) {
                val nextDisplayStart = displaySignals.getOrNull(index + 1)?.range?.first ?: region.length
                val itemRegion = region.substring(display.range.first, nextDisplayStart)
                val imageUrl = sanitizeUrl(display.groupValues[2]).takeIf { !it.startsWith("data:") }
                val videoUrl = itemPattern.findAll(itemRegion)
                    .firstOrNull { it.groupValues[1] == "video_url" }
                    ?.groupValues?.get(2)
                    ?.let(::sanitizeUrl)
                    ?.takeIf { !it.startsWith("data:") }
                if (imageUrl != null || videoUrl != null) {
                    items.add(
                        InstagramCarouselItem(
                            videoUrl = videoUrl,
                            imageUrl = imageUrl,
                            isVideo = videoUrl != null || imageUrl?.endsWith(".mp4") == true
                        )
                    )
                }
            }
        }

        if (items.isEmpty()) {
            for (match in itemPattern.findAll(html)) {
                val url = sanitizeUrl(match.groupValues[2]).takeIf { !it.startsWith("data:") } ?: continue
                if (match.groupValues[1] == "video_url" || url.contains(".mp4")) {
                    items.add(InstagramCarouselItem(videoUrl = url, imageUrl = null, isVideo = true))
                } else {
                    items.add(InstagramCarouselItem(videoUrl = null, imageUrl = url, isVideo = false))
                }
            }
        }

        return items.distinctBy { it.videoUrl ?: it.imageUrl }
    }

    /**
     * Tier 3: Queries Instagram Web API endpoint (?__a=1&__d=dis).
     */
    internal fun extractFromWebApi(shortcode: String, cookies: String? = null): Result<InstagramMediaDetails> {
        val apiUrl = InstagramUrlParser.buildApiUrl(shortcode)
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Referer", "https://www.instagram.com/p/$shortcode/")

        if (!cookies.isNullOrBlank()) {
            requestBuilder.header("Cookie", cookies)
            val csrfToken = Regex("csrftoken=([^;]+)").find(cookies)?.groupValues?.getOrNull(1) ?: ""
            if (csrfToken.isNotBlank()) {
                requestBuilder.header("X-CSRFToken", csrfToken)
            }
        }

        return try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { res ->
                if (res.code == 404) {
                    return Result.failure(AppError.MediaNotFound(apiUrl))
                }
                if (res.code == 401 || res.code == 403) {
                    return Result.failure(AppError.ContentRestricted("Instagram requires login"))
                }

                val json = res.body?.string().orEmpty()
                parseApiResponseJson(shortcode, json)
            }
        } catch (e: IOException) {
            Result.failure(AppError.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM, e))
        }
    }

    /**
     * Parses JSON response from either Web API or Authenticated Mobile API.
     */
    internal fun parseApiResponseJson(shortcode: String, json: String): Result<InstagramMediaDetails> {
        if (json.isBlank() || json.startsWith("<!DOCTYPE") || json.startsWith("<html")) {
            return Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        if (json.contains("\"message\":\"login_required\"") || json.contains("\"status\":\"fail\"")) {
            return Result.failure(AppError.ContentRestricted("Instagram requires login"))
        }

        // Extract video URL (handles single video, video_versions array, sidecar, etc.)
        val videoMatch = Regex(""""url"\s*:\s*"(https?:\\/\\/[^"]+\.mp4[^"]*)"""").find(json)
            ?: Regex(""""url"\s*:\s*"(https?://[^"]+\.mp4[^"]*)"""").find(json)
            ?: Regex("""["']video_url["']\s*:\s*["']([^"']+)["']""").find(json)
            ?: Regex("""(https?:\\/\\/[^"'<>\s]+\.mp4[^"'<>\s]*)""").find(json)
            ?: Regex("""(https?://[^"'<>\s]+\.mp4[^"'<>\s]*)""").find(json)

        val videoUrl = videoMatch?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }?.takeIf { !it.startsWith("data:") }

        // Extract display_url / image / thumbnail
        val imageMatch = Regex(""""url"\s*:\s*"(https?:\\/\\/[^"]+(?:scontent|cdninstagram)[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""").find(json)
            ?: Regex(""""url"\s*:\s*"(https?://[^"]+(?:scontent|cdninstagram)[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"""").find(json)
            ?: Regex("""["']display_url["']\s*:\s*["']([^"']+)["']""").find(json)
            ?: Regex("""["']thumbnail_src["']\s*:\s*["']([^"']+)["']""").find(json)

        val imageUrl = imageMatch?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }?.takeIf { !it.startsWith("data:") }

        // Extract caption text
        val captionMatch = Regex(""""text"\s*:\s*"([^"]{1,500})"""").find(json)
        val title = captionMatch?.groupValues?.getOrNull(1)
            ?.let { unescapeHtmlEntities(unescapeJsonString(it)) }
            ?.take(150)

        // Extract username
        val usernameMatch = Regex(""""username"\s*:\s*"([^"]+)"""").find(json)
        val author = usernameMatch?.groupValues?.getOrNull(1)?.let(::unescapeHtmlEntities)

        val isVideo = !videoUrl.isNullOrBlank()
        if (!isVideo && imageUrl.isNullOrBlank()) {
            return Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        val carouselItems = mutableListOf<InstagramCarouselItem>()
        
        // 1. Try to extract sidecar arrays
        val sidecarMatch = Regex(""""edge_sidecar_to_children"\s*:\s*\{"edges"\s*:\s*\[(.*?)\]\}\}\]""", RegexOption.DOT_MATCHES_ALL).find(json) 
            ?: Regex(""""carousel_media"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL).find(json)
            
        if (sidecarMatch != null) {
            val sidecarJson = sidecarMatch.groupValues[1]
            val nodes = sidecarJson.split(Regex("""\{"node"\s*:|(?=\{"id"\s*:)"""))
            for (node in nodes) {
                val vMatch = Regex("""["']video_url["']\s*:\s*["']([^"']+)["']""").find(node)
                val iMatch = Regex("""["']display_url["']\s*:\s*["']([^"']+)["']""").find(node)
                val vUrl = vMatch?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }?.takeIf { !it.startsWith("data:") }
                val iUrl = iMatch?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }?.takeIf { !it.startsWith("data:") }
                if (vUrl != null || iUrl != null) {
                    carouselItems.add(InstagramCarouselItem(vUrl, iUrl, vUrl != null || iUrl?.contains(".mp4") == true))
                }
            }
        }
        
        // 2. Fallback: permissive regex matching without brace constraints
        if (carouselItems.isEmpty()) {
            val matches = Regex("""(["'])(video_url|display_url)\1\s*:\s*(["'])([^"']+)\3""").findAll(json)
            for (match in matches) {
                val key = match.groupValues[2]
                val url = sanitizeUrl(match.groupValues[4]).takeIf { !it.startsWith("data:") } ?: continue
                val isVid = key == "video_url" || url.contains(".mp4")
                if (isVid) {
                    carouselItems.add(InstagramCarouselItem(videoUrl = url, imageUrl = null, isVideo = true))
                } else {
                    carouselItems.add(InstagramCarouselItem(videoUrl = null, imageUrl = url, isVideo = false))
                }
            }
        }
        
        val distinctCarouselItems = carouselItems.distinctBy { it.videoUrl ?: it.imageUrl }
            .toMutableList()
            
        if (distinctCarouselItems.isNotEmpty()) {
            val hasMain = distinctCarouselItems.any { it.videoUrl == videoUrl || (it.imageUrl == imageUrl && imageUrl != null) }
            if (!hasMain && (videoUrl != null || imageUrl != null)) {
                distinctCarouselItems.add(0, InstagramCarouselItem(videoUrl, imageUrl, isVideo))
            }
        }

        return Result.success(
            InstagramMediaDetails(
                id = shortcode,
                title = title ?: (if (isVideo) "Instagram Reel ($shortcode)" else "Instagram Post ($shortcode)"),
                author = author,
                thumbnailUrl = imageUrl ?: videoUrl,
                videoUrl = videoUrl,
                imageUrl = imageUrl,
                isVideo = isVideo,
                durationSeconds = null,
                carouselItems = carouselItems
            )
        )
    }

    /**
     * Converts an Instagram shortcode to a numeric media ID.
     * Uses BigInteger to prevent 64-bit Long integer overflow on 11+ character shortcodes.
     */
    private fun shortcodeToMediaId(shortcode: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = BigInteger.ZERO
        val sixtyFour = BigInteger.valueOf(64)
        for (char in shortcode) {
            val idx = alphabet.indexOf(char)
            if (idx >= 0) {
                id = id.multiply(sixtyFour).add(BigInteger.valueOf(idx.toLong()))
            }
        }
        return id.toString()
    }

    private fun sanitizeUrl(url: String): String {
        return url.replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .replace("\\", "")
            .trim()
    }

    private fun unescapeHtmlEntities(input: String): String {
        var result = input
        val entityRegex = Regex("""&#([0-9]+);|&#x([0-9a-fA-F]+);|&([a-zA-Z]+);""")
        result = entityRegex.replace(result) { matchResult ->
            val dec = matchResult.groups[1]?.value
            val hex = matchResult.groups[2]?.value
            val named = matchResult.groups[3]?.value
            when {
                dec != null -> {
                    try { dec.toInt().toChar().toString() } catch (e: Exception) { matchResult.value }
                }
                hex != null -> {
                    try { hex.toInt(16).toChar().toString() } catch (e: Exception) { matchResult.value }
                }
                named != null -> {
                    when (named) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        "mdash" -> "—"
                        "ndash" -> "–"
                        "nbsp" -> " "
                        else -> matchResult.value
                    }
                }
                else -> matchResult.value
            }
        }
        return result
    }

    private fun stripHtmlTags(input: String): String {
        return unescapeHtmlEntities(input.replace(Regex("<[^>]*>"), "")).trim()
    }

    private fun unescapeJsonString(input: String): String {
        return input.replace("\\n", " ")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
            .trim()
    }
}
