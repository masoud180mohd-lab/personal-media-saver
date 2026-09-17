package com.mediasaver.core.common

import com.mediasaver.core.model.PlatformId
import java.net.URI

/**
 * High-performance domain and URL parser to identify target media platforms.
 * Adheres to standard JVM URI syntax to allow testing and extraction without Android platform coupling.
 */
object PlatformDetector {

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be"
    )

    private val TIKTOK_HOSTS = setOf(
        "tiktok.com",
        "vm.tiktok.com",
        "vt.tiktok.com",
        "m.tiktok.com"
    )

    private val INSTAGRAM_HOSTS = setOf(
        "instagram.com",
        "instagr.am"
    )

    /**
     * Identifies the platform from an input URL string.
     *
     * @param url The raw input URL
     * @return [PlatformId] matching the target domain or [PlatformId.UNKNOWN]
     */
    fun detect(url: String): PlatformId {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return PlatformId.UNKNOWN

        // WhatsApp is a local file source accessed via SAF, not an external HTTP platform URL.
        // It is recognized only if explicitly tagged or handled via custom local scheme.
        if (cleanUrl.startsWith("whatsapp://", ignoreCase = true) || cleanUrl.startsWith("content://", ignoreCase = true)) {
            return PlatformId.WHATSAPP
        }

        val host = extractHost(cleanUrl) ?: return PlatformId.UNKNOWN
        return detectFromHost(host)
    }

    /**
     * Determines platform given a normalized host string.
     */
    fun detectFromHost(host: String): PlatformId {
        val normalizedHost = host.lowercase().removePrefix("www.")

        if (YOUTUBE_HOSTS.any { normalizedHost == it || normalizedHost.endsWith(".$it") }) {
            return PlatformId.YOUTUBE
        }

        if (TIKTOK_HOSTS.any { normalizedHost == it || normalizedHost.endsWith(".$it") }) {
            return PlatformId.TIKTOK
        }

        if (INSTAGRAM_HOSTS.any { normalizedHost == it || normalizedHost.endsWith(".$it") }) {
            return PlatformId.INSTAGRAM
        }

        return PlatformId.UNKNOWN
    }

    /**
     * Returns true if the URL belongs to a currently supported external download platform.
     */
    fun isSupported(url: String): Boolean {
        val platform = detect(url)
        return platform != PlatformId.UNKNOWN
    }

    private fun extractHost(url: String): String? {
        val candidate = if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            "https://$url"
        } else {
            url
        }

        return runCatching {
            val uri = URI(candidate)
            uri.host ?: candidate.substringAfter("://").substringBefore("/").substringBefore("?")
        }.getOrNull()
    }
}
