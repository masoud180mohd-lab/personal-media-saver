package com.mediasaver.platform.youtube.model

import com.mediasaver.core.model.StreamInfo

/**
 * Encapsulates the media download strategy determined by stream availability and user preferences.
 */
sealed class YouTubeDownloadPlan {

    abstract val qualityLabel: String?

    /**
     * Single stream containing both video and audio.
     * Can be saved directly to disk without requiring multiplexing.
     */
    data class Progressive(
        val stream: StreamInfo
    ) : YouTubeDownloadPlan() {
        override val qualityLabel: String? get() = stream.quality
    }

    /**
     * Dual stream strategy combining a high-definition adaptive video track (e.g. 1080p+)
     * with an independent audio track, requiring FFmpeg multiplexing.
     */
    data class Adaptive(
        val videoStream: StreamInfo,
        val audioStream: StreamInfo
    ) : YouTubeDownloadPlan() {
        override val qualityLabel: String? get() = videoStream.quality
    }
}
