package com.mediasaver.core.common

import java.net.InetAddress

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
        "webp", "m4a", "mp3", "aac", "ogg"
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
        
        return cleanMime in ALLOWED_MIME_TYPES
    }

    /**
     * Checks if a host name or IP string resolves to a private, loopback, link-local,
     * or reserved network address to defend against SSRF (Server-Side Request Forgery).
     */
    fun isLocalOrPrivateIp(host: String?): Boolean {
        if (host.isNullOrBlank()) return true

        val trimmed = host.trim()
        if (trimmed.startsWith('[') != trimmed.endsWith(']')) return true
        val cleanHost = trimmed.removeSurrounding("[", "]").lowercase()
        if (cleanHost.isEmpty() || '[' in cleanHost || ']' in cleanHost) return true

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

        // Malformed literals are invalid. Hostname lookup failures here can also mean
        // Android disallowed DNS on the UI thread. The transport checks its actual DNS answers.
        val literal = ':' in cleanHost || cleanHost.all { it.isDigit() || it == '.' }
        if (literal && ':' !in cleanHost) {
            val parts = cleanHost.split('.')
            if (parts.size != 4 || parts.any { (it.toIntOrNull() ?: -1) !in 0..255 }) return true
        }
        return try {
            InetAddress.getAllByName(cleanHost).any(::isPrivateOrLocalAddress)
        } catch (_: Exception) {
            literal
        }
    }

    fun isPrivateOrLocalAddress(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size == 16) {
            if ((bytes[0].toInt() and 0xfe) == 0xfc) return true
            val mapped = (0 until 10).all { bytes[it] == 0.toByte() } &&
                bytes[10] == (-1).toByte() && bytes[11] == (-1).toByte()
            if (mapped) return isPrivateOrLocalAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 255
            val second = bytes[1].toInt() and 255
            if (first == 0 || first >= 224 || (first == 100 && second in 64..127)) return true
        }
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            address.isAnyLocalAddress || address.isMulticastAddress
    }
}
