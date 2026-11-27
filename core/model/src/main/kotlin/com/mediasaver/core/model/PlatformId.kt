package com.mediasaver.core.model

/**
 * Supported platforms for media extraction and local saving.
 */
enum class PlatformId {
    YOUTUBE,
    TIKTOK,
    WHATSAPP,
    INSTAGRAM,
    FACEBOOK,
    TWITTER,
    PINTEREST,
    UNKNOWN;

    companion object {
        fun fromString(value: String?): PlatformId {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}
