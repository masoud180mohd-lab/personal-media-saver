package com.mediasaver.core.model

/**
 * Domain model representing a persistent download item tracked across process death.
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val platform: PlatformId,
    val mediaId: String,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val selectedQuality: String? = null,
    val selectedFormat: String? = null,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0L,
    val filePath: String? = null,
    val tempFilePath: String? = null,
    val mimeType: String? = null,
    val fileHash: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val durationSeconds: Int? = null
) {
    val progressFraction: Float
        get() = if (totalBytes != null && totalBytes > 0) {
            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val progressPercent: Int
        get() = (progressFraction * 100).toInt()
}
