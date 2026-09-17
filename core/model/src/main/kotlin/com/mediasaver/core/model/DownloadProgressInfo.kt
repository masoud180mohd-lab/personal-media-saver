package com.mediasaver.core.model

/**
 * Progress details for an ongoing download.
 */
data class DownloadProgressInfo(
    val downloadId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val progressPercent: Int,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long? = null,
    val isComplete: Boolean = false
)
