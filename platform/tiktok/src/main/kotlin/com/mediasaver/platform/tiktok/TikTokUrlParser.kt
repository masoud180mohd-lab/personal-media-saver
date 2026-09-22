package com.mediasaver.platform.tiktok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

/**
 * URL parsing and safe redirect-resolution engine for TikTok media links.
 * Resolves short links (vm.tiktok.com, vt.tiktok.com, tiktok.com/t/) and extracts
 * the canonical 15-22 digit video ID.
 */
object TikTokUrlParser {

    private val VIDEO_ID_PATTERN = Pattern.compile("^\\d{15,22}$")

    // Standard video URL: https://www.tiktok.com/@username/video/7123456789012345678
    private val STANDARD_VIDEO_PATTERN = Pattern.compile(
        "^https?://(?:(?:www|m)\\.)?tiktok\\.com/@[^/]+/video/(\\d{15,22})(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    // Mobile web: https://m.tiktok.com/v/7123456789012345678.html
    private val MOBILE_V_PATTERN = Pattern.compile(
        "^https?://(?:(?:www|m)\\.)?tiktok\\.com/v/(\\d{15,22})(?:\\.html)?(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    // Short links: vm.tiktok.com/..., vt.tiktok.com/..., tiktok.com/t/...
    private val SHORT_LINK_PATTERN = Pattern.compile(
        "^https?://(?:(?:vm|vt)\\.tiktok\\.com/|tiktok\\.com/t/)([a-zA-Z0-9_-]+)/?(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Determines whether the given URL is a valid TikTok domain.
     */
    fun isTikTokUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim().lowercase()

        val hasTikTokDomain = trimmed.contains("tiktok.com") ||
                trimmed.contains("vm.tiktok.com") ||
                trimmed.contains("vt.tiktok.com")
        if (!hasTikTokDomain) return false

        return isShortUrl(url) || extractVideoId(url) != null
    }

    /**
     * Determines whether the URL is a shortened TikTok share link requiring HTTP resolution.
     */
    fun isShortUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim()
        return SHORT_LINK_PATTERN.matcher(trimmed).matches() ||
                trimmed.contains("vm.tiktok.com", ignoreCase = true) ||
                trimmed.contains("vt.tiktok.com", ignoreCase = true) ||
                trimmed.contains("tiktok.com/t/", ignoreCase = true)
    }

    /**
     * Extracts numeric video ID directly from URLs already containing the canonical path.
     *
     * @param url Raw input URL
     * @return 15-22 digit video ID string, or null if short link or unmatched
     */
    fun extractVideoId(url: String): String? {
        if (url.isBlank()) return null
        val trimmed = url.trim()

        // 1. Raw numeric ID
        if (VIDEO_ID_PATTERN.matcher(trimmed).matches()) {
            return trimmed
        }

        // 2. Standard @username/video/ID
        STANDARD_VIDEO_PATTERN.matcher(trimmed).let { matcher ->
            if (matcher.matches()) return matcher.group(1)
        }

        // 3. Mobile /v/ID
        MOBILE_V_PATTERN.matcher(trimmed).let { matcher ->
            if (matcher.matches()) return matcher.group(1)
        }

        // 4. Substring fallback for /video/12345
        if (trimmed.contains("/video/")) {
            val candidate = trimmed.substringAfter("/video/")
                .substringBefore("?")
                .substringBefore("#")
                .trim('/')
            if (VIDEO_ID_PATTERN.matcher(candidate).matches()) {
                return candidate
            }
        }

        return null
    }

    /**
     * Safely follows HTTP redirects up to [maxRedirects] (default: 5) to resolve short links
     * into a canonical watch URL containing the video ID.
     */
    suspend fun resolveCanonicalUrl(
        url: String,
        client: OkHttpClient,
        maxRedirects: Int = 5
    ): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        var currentUrl = url.trim()

        // If video ID is already known, return immediately
        if (extractVideoId(currentUrl) != null) {
            return@withContext currentUrl
        }

        // Dedicated client preventing automatic infinite redirect loops
        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        var redirectCount = 0
        while (redirectCount < maxRedirects) {
            val request = Request.Builder()
                .url(currentUrl)
                .head()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .build()

            val response = try {
                noRedirectClient.newCall(request).execute()
            } catch (_: Exception) {
                return@withContext currentUrl.takeIf { extractVideoId(it) != null }
            }

            response.use { resp ->
                val finalUrl = resp.request.url.toString()
                if (extractVideoId(finalUrl) != null) {
                    return@withContext finalUrl
                }

                val code = resp.code
                if (code in 300..399) {
                    val location = resp.header("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                            location
                        } else {
                            val base = resp.request.url
                            base.resolve(location)?.toString() ?: location
                        }
                        redirectCount++

                        // Check if we reached a canonical watch URL
                        if (extractVideoId(currentUrl) != null) {
                            return@withContext currentUrl
                        }
                    } else {
                        return@withContext currentUrl
                    }
                } else {
                    return@withContext finalUrl.ifBlank { currentUrl }
                }
            }
        }

        return@withContext currentUrl
    }

    /**
     * Builds canonical web watch URL.
     */
    fun buildCanonicalWatchUrl(videoId: String, username: String = "user"): String {
        require(VIDEO_ID_PATTERN.matcher(videoId).matches()) { "Invalid TikTok video ID: $videoId" }
        return "https://www.tiktok.com/@$username/video/$videoId"
    }
}
