package com.mediasaver.core.model

/**
 * Metadata for an individual entry inside a media playlist.
 */
data class PlaylistItem(
    val videoId: String,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int? = null,
    val url: String
)

/**
 * Metadata extracted from a content URL prior to download selection.
 */
data class MediaInfo(
    val id: String,
    val platform: PlatformId,
    val title: String?,
    val description: String? = null,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int? = null,
    val sourceUrl: String,
    val playlistItems: List<PlaylistItem> = emptyList(),
    val totalPlaylistCount: Int? = null
) {
    val isPlaylist: Boolean
        get() = playlistItems.isNotEmpty()
}
