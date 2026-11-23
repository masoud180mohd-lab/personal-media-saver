package com.mediasaver.platform.instagram

import java.net.URI

/**
 * Utility for recognizing Instagram URLs and extracting canonical shortcodes.
 * Supports public Posts (/p/), Reels (/reel/, /reels/), IGTV (/tv/), and instagr.am short links.
 */
object InstagramUrlParser {

    private fun uri(url: String): URI? = runCatching {
        val input = url.trim()
        URI(if ("://" in input) input else "https://$input")
    }.getOrNull()?.takeIf {
        it.scheme.lowercase() in setOf("http", "https") && it.host?.lowercase() in
            setOf("instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am", "www.instagr.am")
    }

    fun isInstagramUrl(url: String) = uri(url) != null

    private fun segments(url: String) = uri(url)?.rawPath.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }

    private fun mediaSegment(url: String): Pair<String, String>? {
        val parts = segments(url)
        val mediaTypes = setOf("reel", "reels", "p", "tv")
        val typeIndex = when {
            parts.firstOrNull()?.lowercase() in mediaTypes -> 0
            parts.getOrNull(1)?.lowercase() in mediaTypes -> 1
            else -> return null
        }
        val code = parts.getOrNull(typeIndex + 1)?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) } ?: return null
        return parts[typeIndex].lowercase() to code
    }

    fun extractShortcode(url: String): String? = mediaSegment(url)?.second

    fun isReel(url: String): Boolean = mediaSegment(url)?.first in setOf("reel", "reels")

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
