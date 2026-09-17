package com.mediasaver.core.download.repository

import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.database.entity.DownloadEntity
import com.mediasaver.core.download.queue.DownloadQueueManager
import com.mediasaver.core.download.worker.DownloadWorker
import com.mediasaver.core.domain.repository.DownloadRepository
import com.mediasaver.core.model.DownloadProgressInfo
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val downloadQueueManager: DownloadQueueManager,
    private val downloadDao: DownloadDao,
    private val downloadWorker: DownloadWorker
) : DownloadRepository {

    override fun getActiveDownloads(): Flow<List<DownloadTask>> {
        return downloadQueueManager.activeTasksFlow
    }

    override fun getAllDownloads(): Flow<List<DownloadTask>> {
        return downloadQueueManager.allTasksFlow
    }

    override fun getCompletedDownloads(): Flow<List<DownloadTask>> {
        return downloadDao.observeAll().map { entities ->
            entities.filter { it.status == DownloadStatus.COMPLETED.name }
                .map { it.toDomain() }
        }
    }

    override suspend fun enqueue(
        task: DownloadTask,
        videoStream: StreamInfo,
        audioStream: StreamInfo?
    ) {
        downloadQueueManager.enqueue(task, videoStream, audioStream)
    }

    override suspend fun cancel(downloadId: String) {
        downloadQueueManager.cancel(downloadId)
    }

    override suspend fun retry(downloadId: String) {
        downloadQueueManager.retry(downloadId)
    }

    override suspend fun delete(downloadId: String) {
        downloadDao.deleteById(downloadId)
    }

    override suspend fun clearHistory() {
        downloadDao.clearHistory()
    }

    override fun observeProgress(): Flow<DownloadProgressInfo> {
        return downloadWorker.progressFlow.map { workerProgress ->
            DownloadProgressInfo(
                downloadId = workerProgress.downloadId,
                bytesDownloaded = workerProgress.bytesDownloaded,
                totalBytes = workerProgress.totalBytes,
                progressPercent = workerProgress.progressPercent,
                speedBytesPerSec = workerProgress.speedBytesPerSec,
                etaSeconds = workerProgress.etaSeconds,
                isComplete = workerProgress.isComplete
            )
        }
    }

    private fun DownloadEntity.toDomain(): DownloadTask {
        return DownloadTask(
            id = id,
            url = url,
            platform = PlatformId.fromString(platform),
            mediaId = mediaId,
            title = title,
            thumbnailUrl = thumbnailUrl,
            status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
            selectedQuality = selectedQuality,
            selectedFormat = selectedFormat,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            filePath = filePath,
            tempFilePath = tempFilePath,
            mimeType = mimeType,
            fileHash = fileHash,
            retryCount = retryCount,
            createdAt = createdAt,
            updatedAt = updatedAt,
            completedAt = completedAt,
            durationSeconds = durationSeconds
        )
    }
}
