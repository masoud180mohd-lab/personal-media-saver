package com.mediasaver.platform.instagram

import com.mediasaver.core.common.PlatformDetector
import com.mediasaver.core.model.PlatformId
import java.net.URI

/**
 * Utility for recognizing Instagram URLs and extracting canonical shortcodes.
 * Supports public Posts (/p/), Reels (/reel/, /reels/), IGTV (/tv/), and instagr.am short links.
 */
object InstagramUrlParser {

    private val SHORTCODE_REGEX = Regex(
        """(?:https?://)?(?:www\.|m\.)?(?:instagram\.com|instagr\.am)/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Checks whether the provided URL belongs to Instagram.
     */
    fun isInstagramUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val cleanUrl = url.trim()
        if (PlatformDetector.detect(cleanUrl) == PlatformId.INSTAGRAM) {
            return true
        }
        return SHORTCODE_REGEX.containsMatchIn(cleanUrl)
    }

    /**
     * Extracts the canonical media shortcode (e.g., "C3xYz123abc") from an Instagram URL.
     * Returns null if no valid shortcode can be identified.
     */
    fun extractShortcode(url: String): String? {
        if (url.isBlank()) return null
        val cleanUrl = url.trim()

        val match = SHORTCODE_REGEX.find(cleanUrl)
        if (match != null && match.groupValues.size > 1) {
            val code = match.groupValues[1].trim()
            if (code.isNotEmpty()) return code
        }

        // Fallback segment extraction
        return runCatching {
            val uri = URI(if (!cleanUrl.startsWith("http")) "https://$cleanUrl" else cleanUrl)
            val segments = uri.path?.split("/")?.filter { it.isNotBlank() } ?: emptyList()
            val markerIndex = segments.indexOfFirst { it.equals("p", ignoreCase = true) ||
                    it.equals("reel", ignoreCase = true) ||
                    it.equals("reels", ignoreCase = true) ||
                    it.equals("tv", ignoreCase = true) }
            if (markerIndex >= 0 && markerIndex + 1 < segments.size) {
                segments[markerIndex + 1].substringBefore("?").substringBefore("&").trim()
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * Determines whether the given URL specifically refers to a Reel.
     */
    fun isReel(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/reel/") || lower.contains("/reels/")
    }

    /**
     * Builds the standard public web URL for a shortcode.
     */
    fun buildCanonicalPostUrl(shortcode: String): String {
        return "https://www.instagram.com/p/$shortcode/"
    }

    /**
     * Builds the standard Reel URL for a shortcode.
     */
    fun buildCanonicalReelUrl(shortcode: String): String {
        return "https://www.instagram.com/reel/$shortcode/"
    }

    /**
     * Builds the public embed URL with captioned layout.
     * The embed endpoint is public and typically does not force an immediate login redirect.
     */
    fun buildEmbedUrl(shortcode: String): String {
        return "https://www.instagram.com/p/$shortcode/embed/captioned/"
    }

    /**
     * Builds the web query JSON endpoint URL.
     */
    fun buildApiUrl(shortcode: String): String {
        return "https://www.instagram.com/p/$shortcode/?__a=1&__d=dis"
    }
}
