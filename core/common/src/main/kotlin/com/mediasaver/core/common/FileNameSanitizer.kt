package com.mediasaver.core.common

import com.mediasaver.core.model.PlatformId

/**
 * Sanitizes and formats local media filenames to adhere to Android VFS restrictions,
 * avoiding path traversal, reserved filesystem characters, and length overflows.
 */
object FileNameSanitizer {

    /** Maximum allowed characters for the base name (excluding extension) to avoid NAME_MAX errors. */
    const val MAX_BASE_NAME_LENGTH = 120

    private val RESERVED_CHARS_REGEX = Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    /**
     * Sanitizes a raw title into a safe filesystem string.
     *
     * @param rawTitle Raw content title extracted from platform
     * @param fallback Default string if title is blank or invalid
     * @return Cleaned string safe for filenames
     */
    fun sanitize(rawTitle: String?, fallback: String = "media"): String {
        if (rawTitle.isNullOrBlank()) return fallback

        var cleaned = rawTitle
            .replace(RESERVED_CHARS_REGEX, "_")
            .replace(MULTI_SPACE_REGEX, " ")
            .trim()
            .trimStart('.', '_', '-')
            .trimEnd('.', '_', '-')

        if (cleaned.isBlank()) {
            cleaned = fallback
        }

        return if (cleaned.length > MAX_BASE_NAME_LENGTH) {
            cleaned.take(MAX_BASE_NAME_LENGTH).trimEnd('.', '_', '-')
        } else {
            cleaned
        }
    }

    /**
     * Constructs a complete, organized filename including platform metadata and sanitized extension.
     *
     * @param title The media title
     * @param platform The originating platform
     * @param extension The file extension (e.g. "mp4", ".jpg")
     * @param id Optional media or task ID to ensure uniqueness
     * @return Complete safe filename, e.g. "YouTube_Video_Title_12345.mp4"
     */
    fun buildFileName(
        title: String?,
        platform: PlatformId,
        extension: String,
        id: String? = null
    ): String {
        val safeExt = extension.trimStart('.').lowercase().ifBlank { "mp4" }
        val platformPrefix = when (platform) {
            PlatformId.YOUTUBE -> "YouTube"
            PlatformId.TIKTOK -> "TikTok"
            PlatformId.WHATSAPP -> "WhatsApp"
            PlatformId.INSTAGRAM -> "Instagram"
            PlatformId.UNKNOWN -> "Media"
        }

        val safeTitle = sanitize(title, fallback = "media")
        val uniqueSuffix = id?.takeLast(8)?.let { "_$it" }.orEmpty()

        return "${platformPrefix}_${safeTitle}${uniqueSuffix}.$safeExt"
    }
}
