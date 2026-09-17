package com.mediasaver.core.model

/**
 * Metadata extracted from a content URL prior to download selection.
 */
data class MediaInfo(
    val id: String,
    val platform: PlatformId,
    val title: String?,
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int? = null,
    val sourceUrl: String
)
