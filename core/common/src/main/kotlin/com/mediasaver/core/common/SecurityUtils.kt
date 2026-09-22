package com.mediasaver.core.common

import java.net.InetAddress
import java.net.URI

/**
 * Security utilities enforcing strict defense-in-depth boundaries across URL parsing,
 * network requests, and local filesystem I/O.
 */
object SecurityUtils {

    /** Maximum allowed download size (2 GB) to prevent storage exhaustion or zip bombs. */
    const val MAX_DOWNLOAD_SIZE_BYTES = 2L * 1024 * 1024 * 1024

    /** Maximum allowed HTTP redirect hops to prevent circular redirection attacks. */
    const val MAX_REDIRECT_HOPS = 5

    /** Whitelist of strictly permitted media file extensions. */
    val ALLOWED_EXTENSIONS = setOf(
        "mp4",
        "webm",
        "jpg",
        "jpeg",
        "png",
        "webp"
    )

    /** Whitelist of permitted MIME type prefixes and full types. */
    val ALLOWED_MIME_TYPES = setOf(
        "video/mp4",
        "video/webm",
        "video/quicktime",
        "video/x-matroska",
        "video/3gpp",
        "audio/mp4",
        "audio/webm",
        "audio/m4a",
        "audio/aac",
        "audio/mpeg",
        "audio/ogg",
        "image/jpeg",
        "image/png",
        "image/webp",
        "application/octet-stream"
    )

    /** Dangerous URI schemes that must be rejected immediately. */
    private val DANGEROUS_SCHEMES = setOf(
        "javascript",
        "file",
        "content",
        "intent",
        "data",
        "blob",
        "about",
        "ftp",
        "ws",
        "wss",
        "tel",
        "sms",
        "mailto"
    )

    /**
     * Checks whether a scheme is classified as dangerous.
     */
    fun isDangerousScheme(scheme: String?): Boolean {
        if (scheme.isNullOrBlank()) return false
        val normalized = scheme.lowercase().trim().trimEnd(':')
        return DANGEROUS_SCHEMES.contains(normalized)
    }

    /**
     * Validates if a file extension is in the strict whitelist.
     *
     * @param extension Extension with or without leading dot (e.g. ".mp4" or "mp4")
     */
    fun isValidExtension(extension: String?): Boolean {
        if (extension.isNullOrBlank()) return false
        val cleaned = extension.trim().trimStart('.').lowercase()
        return ALLOWED_EXTENSIONS.contains(cleaned)
    }

    /**
     * Normalizes a file extension, falling back to [defaultExtension] if invalid or not allowed.
     */
    fun normalizeExtension(extension: String?, defaultExtension: String = "mp4"): String {
        val cleaned = extension?.trim()?.trimStart('.')?.lowercase()
        return if (isValidExtension(cleaned)) {
            cleaned!!
        } else {
            defaultExtension.trimStart('.').lowercase()
        }
    }

    /**
     * Validates that the Content-Type header from an HTTP response is a permitted media type.
     * Rejects executable, HTML, script, or unknown document payloads.
     */
    fun isAllowedMimeType(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return true // Allow empty/missing, falls back to stream analysis
        val cleanMime = mimeType.split(";").first().trim().lowercase()
        
        // Explicit rejection of active/executable payloads
        if (cleanMime.startsWith("text/") && cleanMime != "text/plain") return false
        if (cleanMime.contains("javascript") || cleanMime.contains("html") || cleanMime.contains("json")) return false
        if (cleanMime.contains("executable") || cleanMime.contains("x-msdownload") || cleanMime.contains("application/x-")) return false

        return ALLOWED_MIME_TYPES.contains(cleanMime) ||
                cleanMime.startsWith("video/") ||
                cleanMime.startsWith("audio/") ||
                cleanMime.startsWith("image/")
    }

    /**
     * Checks if a host name or IP string resolves to a private, loopback, link-local,
     * or reserved network address to defend against SSRF (Server-Side Request Forgery).
     */
    fun isLocalOrPrivateIp(host: String?): Boolean {
        if (host.isNullOrBlank()) return true

        val cleanHost = host.trim().trim('[', ']').lowercase()

        // Fast string matches for common local identifiers
        if (cleanHost == "localhost" ||
            cleanHost == "127.0.0.1" ||
            cleanHost == "0.0.0.0" ||
            cleanHost == "::1" ||
            cleanHost == "0:0:0:0:0:0:0:1" ||
            cleanHost == "0:0:0:0:0:0:0:0" ||
            cleanHost.endsWith(".localhost") ||
            cleanHost.endsWith(".local") ||
            cleanHost.endsWith(".internal")
        ) {
            return true
        }

        // Direct numeric IPv4 octet checks to avoid unnecessary DNS resolution for literals
        val ipv4Parts = cleanHost.split(".")
        if (ipv4Parts.size == 4 && ipv4Parts.all { it.toIntOrNull() != null }) {
            val octets = ipv4Parts.map { it.toInt() }
            val first = octets[0]
            val second = octets[1]

            // 127.0.0.0/8 (Loopback)
            if (first == 127) return true
            // 10.0.0.0/8 (Private)
            if (first == 10) return true
            // 172.16.0.0/12 (Private)
            if (first == 172 && second in 16..31) return true
            // 192.168.0.0/16 (Private)
            if (first == 192 && second == 168) return true
            // 169.254.0.0/16 (Link-Local / APIPA)
            if (first == 169 && second == 254) return true
            // 0.0.0.0/8 (Current network)
            if (first == 0) return true
            // 100.64.0.0/10 (Carrier-grade NAT)
            if (first == 100 && second in 64..127) return true
            // 224.0.0.0/4 (Multicast) & 240.0.0.0/4 (Reserved)
            if (first >= 224) return true
        }

        return try {
            val addresses = InetAddress.getAllByName(cleanHost)
            addresses.any { isPrivateOrLocalAddress(it) }
        } catch (_: Exception) {
            // If host cannot be resolved, return false here and let HTTP request handle resolution failure
            false
        }
    }

    /**
     * Evaluates whether an [InetAddress] is loopback, site-local, link-local, multicast, or any-local.
     */
    fun isPrivateOrLocalAddress(address: InetAddress): Boolean {
        return address.isLoopbackAddress ||
                address.isSiteLocalAddress ||
                address.isLinkLocalAddress ||
                address.isAnyLocalAddress ||
                address.isMulticastAddress
    }
}
