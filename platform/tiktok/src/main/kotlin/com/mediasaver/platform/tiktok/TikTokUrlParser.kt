package com.mediasaver.platform.tiktok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.mediasaver.core.network.interceptor.PlatformWebDomain
import java.util.regex.Pattern

/**
 * URL parsing and safe redirect-resolution engine for TikTok media links.
 * Resolves short links (vm.tiktok.com, vt.tiktok.com, tiktok.com/t/) and extracts
 * the canonical 15-22 digit video ID.
 */
object TikTokUrlParser {

    private val VIDEO_ID_PATTERN = Pattern.compile("^\\d{15,22}$")

    // Standard video or photo post URL: https://www.tiktok.com/@username/video/7123... or /photo/7123...
    private val STANDARD_VIDEO_PATTERN = Pattern.compile(
        "^https?://(?:(?:www|m)\\.)?tiktok\\.com/@[^/]+/(?:video|photo)/(\\d{15,22})(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    // Mobile web: https://m.tiktok.com/v/7123456789012345678.html
    private val MOBILE_V_PATTERN = Pattern.compile(
        "^https?://(?:(?:www|m)\\.)?tiktok\\.com/v/(\\d{15,22})(?:\\.html)?(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    // Short links: vm.tiktok.com/..., vt.tiktok.com/..., tiktok.com/t/...
    private val SHORT_LINK_PATTERN = Pattern.compile(
        "^https?://(?:(?:vm|vt)\\.tiktok\\.com/|(?:www\\.)?tiktok\\.com/t/)([a-zA-Z0-9_-]+)/?(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Determines whether the given URL is a valid TikTok domain.
     */
    fun isTikTokUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val host = url.trim().toHttpUrlOrNull()?.host ?: return false
        if (!PlatformWebDomain("tiktok.com").accepts(host)) return false
        return isShortUrl(url) || extractVideoId(url) != null
    }

    /**
     * Determines whether the URL is a shortened TikTok share link requiring HTTP resolution.
     */
    fun isShortUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim()
        return SHORT_LINK_PATTERN.matcher(trimmed).matches()
    }

    /**
     * Determines whether the URL is a TikTok photo slideshow post (/photo/).
     */
    fun isPhotoUrl(url: String): Boolean = url.contains("/photo/", ignoreCase = true)

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

        // 2. Standard @username/video/ID or @username/photo/ID
        STANDARD_VIDEO_PATTERN.matcher(trimmed).let { matcher ->
            if (matcher.matches()) return matcher.group(1)
        }

        // 3. Mobile /v/ID
        MOBILE_V_PATTERN.matcher(trimmed).let { matcher ->
            if (matcher.matches()) return matcher.group(1)
        }

        // 4. Substring fallback for /video/12345 or /photo/12345
        val marker = when {
            "/video/" in trimmed -> "/video/"
            "/photo/" in trimmed -> "/photo/"
            else -> null
        }
        if (marker != null) {
            val candidate = trimmed.substringAfter(marker)
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
     * Resolves share links using the shared HTTPS client and its five-hop redirect policy.
     */
    suspend fun resolveCanonicalUrl(url: String, client: OkHttpClient): String? = withContext(Dispatchers.IO) {
        if (!isTikTokUrl(url)) return@withContext null
        if (!isShortUrl(url)) return@withContext url

        val request = Request.Builder().url(url).head()
            .tag(PlatformWebDomain::class.java, PlatformWebDomain("tiktok.com"))
            .header("User-Agent", "Mozilla/5.0")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val resolved = response.request.url
                resolved.newBuilder().query(null).fragment(null).build().toString().takeIf {
                    isTikTokUrl(it) && extractVideoId(it) != null
                }
            }
        } catch (_: java.io.IOException) {
            null
        }
    }

    /**
     * Builds canonical web watch URL.
     */
    fun buildCanonicalWatchUrl(videoId: String, username: String = "user"): String {
        require(VIDEO_ID_PATTERN.matcher(videoId).matches()) { "Invalid TikTok video ID: $videoId" }
        return "https://www.tiktok.com/@$username/video/$videoId"
    }
}
