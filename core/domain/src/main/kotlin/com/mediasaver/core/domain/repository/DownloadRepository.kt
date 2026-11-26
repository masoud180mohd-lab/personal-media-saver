package com.mediasaver.core.domain.repository

import com.mediasaver.core.model.DownloadProgressInfo
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.StreamInfo
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for managing downloads and tracking download states.
 */
interface DownloadRepository {
    fun getActiveDownloads(): Flow<List<DownloadTask>>
    fun getAllDownloads(): Flow<List<DownloadTask>>
    fun getCompletedDownloads(): Flow<List<DownloadTask>>
    suspend fun enqueue(task: DownloadTask, videoStream: StreamInfo, audioStream: StreamInfo? = null)
    suspend fun pause(downloadId: String)
    suspend fun resume(downloadId: String)
    suspend fun pauseAll()
    suspend fun resumeAll()
    suspend fun cancel(downloadId: String)
    suspend fun cancelAll()
    suspend fun retry(downloadId: String)
    suspend fun retryAllFailed()
    suspend fun delete(downloadId: String)
    suspend fun clearHistory()
    fun observeProgress(): Flow<DownloadProgressInfo>
}
