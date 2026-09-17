package com.mediasaver.platform.youtube

import java.util.regex.Pattern

/**
 * Robust URL parser and canonicalizer for YouTube links.
 * Extracts standard 11-character video IDs across standard, mobile, music, short-link,
 * Shorts, live stream, and embed patterns.
 */
object YouTubeUrlParser {

    private val VIDEO_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{11}$")

    // Matchers for various YouTube URL patterns
    private val WATCH_URL_PATTERN = Pattern.compile(
        "^https?://(?:(?:www|m|music)\\.)?youtube\\.com/watch.*[?&]v=([a-zA-Z0-9_-]{11})(?:[&#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    private val SHORT_LINK_PATTERN = Pattern.compile(
        "^https?://youtu\\.be/([a-zA-Z0-9_-]{11})(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    private val SHORTS_PATTERN = Pattern.compile(
        "^https?://(?:(?:www|m)\\.)?youtube\\.com/shorts/([a-zA-Z0-9_-]{11})(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    private val LIVE_PATTERN = Pattern.compile(
        "^https?://(?:(?:www|m)\\.)?youtube\\.com/live/([a-zA-Z0-9_-]{11})(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    private val EMBED_PATTERN = Pattern.compile(
        "^https?://(?:(?:www|m)\\.)?youtube\\.com/embed/([a-zA-Z0-9_-]{11})(?:[?#].*)?$",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Determines whether the given URL matches a known YouTube domain and structure.
     */
    fun isYouTubeUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim()
        val lower = trimmed.lowercase()

        val isYouTubeDomain = lower.contains("youtube.com") || lower.contains("youtu.be")
        if (!isYouTubeDomain) return false

        return extractVideoId(trimmed) != null
    }

    /**
     * Extracts the canonical 11-character YouTube video ID.
     *
     * @param url Raw input URL
     * @return Canonical 11-character ID or null if not matching
     */
    fun extractVideoId(url: String): String? {
        if (url.isBlank()) return null
        val trimmed = url.trim()

        // 1. Check if input is already a naked 11-char ID
        if (VIDEO_ID_PATTERN.matcher(trimmed).matches()) {
            return trimmed
        }

        // 2. youtu.be/<id>
        SHORT_LINK_PATTERN.matcher(trimmed).let { matcher ->
            if (matcher.matches()) return matcher.group(1)
        }

        // 3. youtube.com/watch?v=<id>
        WATCH_URL_PATTERN.matcher(trimmed).let { matcher ->
            if (matcher.matches()) return matcher.group(1)
        }

        // 4. youtube.com/shorts/<id>
        SHORTS_PATTERN.matcher(trimmed).let { matcher ->
            if (matcher.matches()) return matcher.group(1)
        }

        // 5. youtube.com/live/<id>
        LIVE_PATTERN.matcher(trimmed).let { matcher ->
            if (matcher.matches()) return matcher.group(1)
        }

        // 6. youtube.com/embed/<id>
        EMBED_PATTERN.matcher(trimmed).let { matcher ->
            if (matcher.matches()) return matcher.group(1)
        }

        // 7. General fallback parameter extraction for non-standard parameter orders
        return extractParameterFallback(trimmed)
    }

    private fun extractParameterFallback(url: String): String? {
        if (!url.contains("youtube.com", ignoreCase = true) && !url.contains("youtu.be", ignoreCase = true)) {
            return null
        }

        // Try extracting query parameter 'v'
        val queryPart = url.substringAfter("?", "").substringBefore("#")
        if (queryPart.isNotBlank()) {
            val params = queryPart.split("&")
            for (param in params) {
                val parts = param.split("=")
                if (parts.size == 2 && parts[0] == "v") {
                    val candidate = parts[1]
                    if (VIDEO_ID_PATTERN.matcher(candidate).matches()) {
                        return candidate
                    }
                }
            }
        }

        // Try extracting path segments for shorts/live/embed
        val pathSegments = url.substringAfter("://").substringAfter("/").substringBefore("?").substringBefore("#").split("/")
        for (i in 0 until pathSegments.size - 1) {
            val segment = pathSegments[i].lowercase()
            if (segment == "shorts" || segment == "live" || segment == "embed") {
                val candidate = pathSegments[i + 1]
                if (VIDEO_ID_PATTERN.matcher(candidate).matches()) {
                    return candidate
                }
            }
        }

        return null
    }

    /**
     * Builds standard web watch URL from canonical video ID.
     */
    fun buildCanonicalWatchUrl(videoId: String): String {
        require(VIDEO_ID_PATTERN.matcher(videoId).matches()) { "Invalid YouTube video ID: $videoId" }
        return "https://www.youtube.com/watch?v=$videoId"
    }
}
