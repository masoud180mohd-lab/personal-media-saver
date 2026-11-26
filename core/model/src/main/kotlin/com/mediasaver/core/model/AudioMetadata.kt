package com.mediasaver.core.model

/**
 * Metadata embedded into saved audio files and written to MediaStore audio columns.
 */
data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val totalTracks: Int? = null,
    val coverArtUrl: String? = null
) {
    val hasAnyTag: Boolean
        get() = !title.isNullOrBlank() ||
            !artist.isNullOrBlank() ||
            !album.isNullOrBlank() ||
            !coverArtUrl.isNullOrBlank() ||
            (trackNumber != null && trackNumber > 0)
}
