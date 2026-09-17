package com.mediasaver.platform.instagram

import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

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
    val durationSeconds: Int? = null
)

/**
 * Public web extraction engine for Instagram posts and reels.
 * Utilizes a 3-tier fallback strategy (Embed captioned page, OpenGraph post HTML, and Web API),
 * with robust parsing for HTML5 media tags, OpenGraph metadata, and embedded JSON structures.
 * Strictly respects zero-telemetry and zero-stored-credential architecture.
 */
@Singleton
class InstagramExtractor @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val IG_APP_ID = "936619743392459"
    }

    /**
     * Extracts public media information for a given shortcode.
     */
    suspend fun extract(shortcode: String): Result<InstagramMediaDetails> = withContext(Dispatchers.IO) {
        var isLoginRedirect = false

        // Tier 1: Public Embed Page (Most reliable for public content without immediate login wall)
        val embedResult = extractFromEmbedPage(shortcode)
        if (embedResult.isSuccess) {
            return@withContext embedResult
        }
        if (embedResult.exceptionOrNull() is AppError.ContentRestricted) {
            isLoginRedirect = true
        }

        // Tier 2: Public Canonical Post/Reel HTML (OpenGraph & embedded JSON)
        val postHtmlResult = extractFromPostHtml(shortcode)
        if (postHtmlResult.isSuccess) {
            return@withContext postHtmlResult
        }
        if (postHtmlResult.exceptionOrNull() is AppError.ContentRestricted) {
            isLoginRedirect = true
        }

        // Tier 3: Web API query (?__a=1&__d=dis)
        val apiResult = extractFromWebApi(shortcode)
        if (apiResult.isSuccess) {
            return@withContext apiResult
        }
        if (apiResult.exceptionOrNull() is AppError.ContentRestricted) {
            isLoginRedirect = true
        }

        if (isLoginRedirect) {
            Result.failure(AppError.ContentRestricted("Instagram requires login or this post is private"))
        } else {
            Result.failure(
                embedResult.exceptionOrNull()
                    ?: postHtmlResult.exceptionOrNull()
                    ?: AppError.ExtractionFailed(PlatformId.INSTAGRAM)
            )
        }
    }

    /**
     * Tier 1: Extracts media from the public embed endpoint:
     * https://www.instagram.com/p/{shortcode}/embed/captioned/
     */
    internal fun extractFromEmbedPage(shortcode: String): Result<InstagramMediaDetails> {
        val embedUrl = InstagramUrlParser.buildEmbedUrl(shortcode)
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.instagram.com/")
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (res.code == 404) {
                    return Result.failure(AppError.MediaNotFound(embedUrl))
                }

                val finalUrl = res.request.url.toString()
                if (finalUrl.contains("/accounts/login/") || finalUrl.contains("/challenge/")) {
                    return Result.failure(AppError.ContentRestricted("Instagram requires login or this post is private"))
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
     * Parses the embed page HTML content.
     */
    internal fun parseEmbedHtml(shortcode: String, html: String): Result<InstagramMediaDetails> {
        if (html.isBlank()) {
            return Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        if (html.contains("Login &bull; Instagram") || html.contains("accounts/login") || html.contains("checkpoint_required")) {
            return Result.failure(AppError.ContentRestricted("Instagram requires login or this post is private"))
        }

        // 1. Direct Video Tag
        val videoUrlMatch = Regex("""<video[^>]+class="[^"]*EmbeddedMediaVideo[^"]*"[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<video[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""["']video_url["']\s*:\s*["']([^"']+)["']""").find(html)

        val videoUrl = videoUrlMatch?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }

        // 2. Direct Image / Thumbnail Tag
        val imgMatch = Regex("""<img[^>]+class="[^"]*EmbeddedMediaImage[^"]*"[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<img[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""["']display_url["']\s*:\s*["']([^"']+)["']""").find(html)

        val imageUrl = imgMatch?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }

        // 3. Caption / Title
        val captionMatch = Regex("""<div[^>]+class="[^"]*Caption[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<div[^>]+class="[^"]*CaptionText[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL or RegexOption.IGNORE_CASE).find(html)

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
     * Tier 2: Extracts media from canonical post or reel HTML page via OpenGraph tags.
     */
    internal fun extractFromPostHtml(shortcode: String): Result<InstagramMediaDetails> {
        val postUrl = InstagramUrlParser.buildCanonicalPostUrl(shortcode)
        val request = Request.Builder()
            .url(postUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (res.code == 404) {
                    return Result.failure(AppError.MediaNotFound(postUrl))
                }

                val finalUrl = res.request.url.toString()
                if (finalUrl.contains("/accounts/login/")) {
                    return Result.failure(AppError.ContentRestricted("Instagram requires login or this post is private"))
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
     * Parses post HTML for OpenGraph meta tags and JSON-LD data.
     */
    internal fun parsePostHtml(shortcode: String, html: String): Result<InstagramMediaDetails> {
        if (html.isBlank()) {
            return Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        if (html.contains("Login &bull; Instagram") || html.contains("accounts/login")) {
            return Result.failure(AppError.ContentRestricted("Instagram requires login or this post is private"))
        }

        // OpenGraph Video
        val ogVideo = Regex("""<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+property=["']og:video:secure_url["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:video["']""", RegexOption.IGNORE_CASE).find(html)

        val videoUrl = ogVideo?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }

        // OpenGraph Image
        val ogImage = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""", RegexOption.IGNORE_CASE).find(html)

        val imageUrl = ogImage?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }

        // OpenGraph Title & Description
        val ogTitle = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
        val ogDesc = Regex("""<meta[^>]+property=["']og:description["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)

        val rawTitle = ogTitle?.groupValues?.getOrNull(1) ?: ogDesc?.groupValues?.getOrNull(1)
        val title = rawTitle?.let { stripHtmlTags(it) }?.take(150)?.ifBlank { null }

        val author = ogTitle?.groupValues?.getOrNull(1)?.let {
            if (it.contains("on Instagram:")) {
                it.substringBefore("on Instagram:").trim().removePrefix("@")
            } else {
                null
            }
        }

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
     * Tier 3: Queries Instagram Web API endpoint with X-IG-App-ID header.
     */
    internal fun extractFromWebApi(shortcode: String): Result<InstagramMediaDetails> {
        val apiUrl = InstagramUrlParser.buildApiUrl(shortcode)
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .header("Referer", "https://www.instagram.com/p/$shortcode/")
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (res.code == 404) {
                    return Result.failure(AppError.MediaNotFound(apiUrl))
                }
                if (res.code == 401 || res.code == 403) {
                    return Result.failure(AppError.ContentRestricted("Instagram requires login or this post is private"))
                }

                val json = res.body?.string().orEmpty()
                parseWebApiResponse(shortcode, json)
            }
        } catch (e: IOException) {
            Result.failure(AppError.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM, e))
        }
    }

    /**
     * Parses JSON response from Web API.
     */
    internal fun parseWebApiResponse(shortcode: String, json: String): Result<InstagramMediaDetails> {
        if (json.isBlank() || json.startsWith("<!DOCTYPE") || json.startsWith("<html")) {
            return Result.failure(AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

        // Check for login required in JSON response
        if (json.contains("\"message\":\"login_required\"") || json.contains("\"status\":\"fail\"")) {
            return Result.failure(AppError.ContentRestricted("Instagram requires login or this post is private"))
        }

        // Extract video_url
        val videoMatch = Regex("""["']video_url["']\s*:\s*["']([^"']+)["']""").find(json)
            ?: Regex("""["']url["']\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(json)

        val videoUrl = videoMatch?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }

        // Extract display_url / image
        val imageMatch = Regex("""["']display_url["']\s*:\s*["']([^"']+)["']""").find(json)
            ?: Regex("""["']thumbnail_src["']\s*:\s*["']([^"']+)["']""").find(json)

        val imageUrl = imageMatch?.groupValues?.getOrNull(1)?.let { sanitizeUrl(it) }

        // Extract caption text
        val captionMatch = Regex("""["']text["']\s*:\s*["']([^"']+)["']""").find(json)
        val title = captionMatch?.groupValues?.getOrNull(1)?.let { unescapeJsonString(it) }?.take(150)

        // Extract username
        val usernameMatch = Regex("""["']username["']\s*:\s*["']([^"']+)["']""").find(json)
        val author = usernameMatch?.groupValues?.getOrNull(1)

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

    private fun sanitizeUrl(url: String): String {
        return url.replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .trim()
    }

    private fun stripHtmlTags(input: String): String {
        return input.replace(Regex("<[^>]*>"), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .trim()
    }

    private fun unescapeJsonString(input: String): String {
        return input.replace("\\n", " ")
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
            .trim()
    }
}
