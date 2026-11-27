package com.mediasaver.core.model

import java.util.Locale

/**
 * Snapshot of the in-app media player across audio, background service, and video overlay.
 */
data class PlaybackState(
    val currentItem: DownloadTask? = null,
    val queue: List<DownloadTask> = emptyList(),
    val isVideo: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val errorMessage: String? = null
) {
    val hasActiveItem: Boolean
        get() = currentItem != null

    val hasNext: Boolean
        get() {
            val currentId = currentItem?.id ?: return false
            val index = queue.indexOfFirst { it.id == currentId }
            return index in 0 until queue.lastIndex
        }

    val hasPrevious: Boolean
        get() {
            val currentId = currentItem?.id ?: return false
            return queue.indexOfFirst { it.id == currentId } > 0
        }
}

val SUPPORTED_PLAYBACK_SPEEDS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

fun shouldResetSavedPosition(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0L && positionMs >= (durationMs - 5_000L).coerceAtLeast(0L)
