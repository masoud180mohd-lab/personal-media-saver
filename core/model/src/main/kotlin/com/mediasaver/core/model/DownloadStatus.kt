package com.mediasaver.core.model

/**
 * Lifecycle states of a download task.
 */
enum class DownloadStatus {
    /** Queued in Room DB awaiting worker execution. */
    QUEUED,

    /** Active stream resolution and metadata extraction. */
    EXTRACTING,

    /** Downloading media payload chunks via OkHttp streaming. */
    DOWNLOADING,

    /** Combining video and audio tracks via FFmpeg (for adaptive 1080p+ streams). */
    MUXING,

    /** Successfully saved to destination (MediaStore / local gallery). */
    COMPLETED,

    /** Failed due to network, extraction, or filesystem error. */
    FAILED,

    /** Cancelled by user action. */
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isActive: Boolean
        get() = this == EXTRACTING || this == DOWNLOADING || this == MUXING
}
