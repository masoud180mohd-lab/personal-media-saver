package com.mediasaver.core.download.queue

import android.content.Context
import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.database.dao.StreamOptionDao
import com.mediasaver.core.database.entity.DownloadEntity
import com.mediasaver.core.database.entity.StreamOptionEntity
import com.mediasaver.core.download.retry.RetryPolicy
import com.mediasaver.core.download.service.DownloadService
import com.mediasaver.core.download.worker.DownloadProgress
import com.mediasaver.core.download.worker.DownloadWorker
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.storage.TempFileManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent download queue manager backed by Room.
 * Coordinates concurrency limits, FIFO task scheduling, priority overrides,
 * automatic backoff retries, and recovers gracefully across process termination.
 */
@Singleton
class DownloadQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val streamOptionDao: StreamOptionDao,
    private val downloadWorker: DownloadWorker,
    private val tempFileManager: TempFileManager,
    private val retryPolicy: RetryPolicy
) {

    private val queueScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queueMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    var maxConcurrentDownloads: Int = 3

    init {
        recoverQueueState()
    }

    /**
     * Observable stream of all active (queued, downloading, muxing) tasks.
     */
    val activeTasksFlow: Flow<List<DownloadTask>> = downloadDao.observeActiveDownloads()
        .map { entities -> entities.map { it.toDomain() } }

    /**
     * Observable stream of the entire download history.
     */
    val allTasksFlow: Flow<List<DownloadTask>> = downloadDao.observeAll()
        .map { entities -> entities.map { it.toDomain() } }

    /**
     * Enqueues a new media download item.
     *
     * @param task Domain task definition
     * @param videoStream Chosen video stream
     * @param audioStream Optional audio stream for adaptive muxing
     */
    suspend fun enqueue(
        task: DownloadTask,
        videoStream: StreamInfo,
        audioStream: StreamInfo? = null
    ) {
        queueMutex.withLock {
            val entity = task.toEntity().copy(status = DownloadStatus.QUEUED.name)
            downloadDao.insert(entity)

            val streamEntities = mutableListOf<StreamOptionEntity>()
            streamEntities.add(
                StreamOptionEntity(
                    id = "${task.id}_video",
                    downloadId = task.id,
                    streamUrl = videoStream.url,
                    quality = videoStream.quality,
                    format = videoStream.format,
                    mimeType = videoStream.mimeType,
                    isVideoOnly = videoStream.isVideoOnly,
                    isAudioOnly = false,
                    fileSize = videoStream.fileSize,
                    bitrate = videoStream.bitrate,
                    codec = videoStream.codec
                )
            )

            if (audioStream != null) {
                streamEntities.add(
                    StreamOptionEntity(
                        id = "${task.id}_audio",
                        downloadId = task.id,
                        streamUrl = audioStream.url,
                        quality = audioStream.quality,
                        format = audioStream.format,
                        mimeType = audioStream.mimeType,
                        isVideoOnly = false,
                        isAudioOnly = true,
                        fileSize = audioStream.fileSize,
                        bitrate = audioStream.bitrate,
                        codec = audioStream.codec
                    )
                )
            }

            streamOptionDao.insertAll(streamEntities)
        }

        DownloadService.start(context)
        processNextInQueue()
    }

    /**
     * Cancels an active or queued download.
     */
    suspend fun cancel(downloadId: String) {
        val job = activeJobs.remove(downloadId)
        job?.cancel()

        downloadDao.markCancelled(downloadId)
        tempFileManager.deleteTempFile(tempFileManager.createTempDownloadFile(downloadId))
        processNextInQueue()
    }

    /**
     * Re-queues a failed or cancelled download task.
     * Manual retry is always permitted and resets the retry counter.
     */
    suspend fun retry(downloadId: String) {
        queueMutex.withLock {
            downloadDao.updateRetryCount(downloadId, 0)
            downloadDao.updateStatus(downloadId, DownloadStatus.QUEUED.name)
        }
        DownloadService.start(context)
        processNextInQueue()
    }

    /**
     * Promotes a task to the front of the FIFO queue by adjusting its creation timestamp.
     */
    suspend fun prioritize(downloadId: String) {
        queueMutex.withLock {
            val task = downloadDao.getById(downloadId) ?: return
            downloadDao.update(task.copy(createdAt = 0L))
        }
        processNextInQueue()
    }

    /**
     * Recovers from process death or application crash by resetting interrupted downloads
     * and sweeping orphaned temporary disk files.
     */
    fun recoverQueueState() {
        queueScope.launch {
            var hasActive = false
            queueMutex.withLock {
                // Delete orphaned temp files older than 6 hours
                tempFileManager.cleanOrphanedTempFiles(maxAgeMillis = 6 * 60 * 60 * 1000L)

                // Any task stuck in EXTRACTING, DOWNLOADING or MUXING when app boots was interrupted by process death
                val activeDownloads = downloadDao.findActiveDownloads()
                for (download in activeDownloads) {
                    if (download.status == DownloadStatus.EXTRACTING.name ||
                        download.status == DownloadStatus.DOWNLOADING.name ||
                        download.status == DownloadStatus.MUXING.name
                    ) {
                        downloadDao.updateStatus(download.id, DownloadStatus.QUEUED.name)
                    }
                }
                hasActive = downloadDao.findActiveDownloads().isNotEmpty()
            }
            if (hasActive) {
                DownloadService.start(context)
            }
            processNextInQueue()
        }
    }

    /**
     * Schedules the next eligible queued tasks up to the concurrency limit.
     */
    private fun processNextInQueue() {
        queueScope.launch {
            queueMutex.withLock {
                val currentRunning = activeJobs.size
                if (currentRunning >= maxConcurrentDownloads) return@withLock

                val capacity = maxConcurrentDownloads - currentRunning
                val activeDownloads = downloadDao.findActiveDownloads()
                val queuedTasks = activeDownloads.filter { it.status == DownloadStatus.QUEUED.name }
                    .take(capacity)

                for (taskEntity in queuedTasks) {
                    val downloadId = taskEntity.id
                    if (activeJobs.containsKey(downloadId)) continue

                    val streams = streamOptionDao.getByDownloadId(downloadId)
                    val videoStreamEntity = streams.firstOrNull { !it.isAudioOnly }
                    val audioStreamEntity = streams.firstOrNull { it.isAudioOnly }

                    if (videoStreamEntity == null) {
                        downloadDao.markFailed(downloadId, "MissingStream", "No stream URL found for task")
                        continue
                    }

                    val videoStream = StreamInfo(
                        url = videoStreamEntity.streamUrl,
                        quality = videoStreamEntity.quality,
                        format = videoStreamEntity.format,
                        mimeType = videoStreamEntity.mimeType,
                        isVideoOnly = videoStreamEntity.isVideoOnly,
                        isAudioOnly = false,
                        fileSize = videoStreamEntity.fileSize,
                        bitrate = videoStreamEntity.bitrate,
                        codec = videoStreamEntity.codec
                    )

                    val audioStream = audioStreamEntity?.let {
                        StreamInfo(
                            url = it.streamUrl,
                            quality = it.quality,
                            format = it.format,
                            mimeType = it.mimeType,
                            isVideoOnly = false,
                            isAudioOnly = true,
                            fileSize = it.fileSize,
                            bitrate = it.bitrate,
                            codec = it.codec
                        )
                    }

                    val targetFileName = "${taskEntity.title ?: "media"}.${videoStream.format ?: "mp4"}"
                    val platform = PlatformId.fromString(taskEntity.platform)

                    val job = queueScope.launch {
                        try {
                            val result = downloadWorker.executeDownload(
                                downloadId = downloadId,
                                targetFileName = targetFileName,
                                platform = platform,
                                videoStream = videoStream,
                                audioStream = audioStream
                            )
                            if (result.isFailure) {
                                val error = result.exceptionOrNull() ?: Exception("Download failed")
                                val task = downloadDao.getById(downloadId)
                                val currentAttempt = task?.retryCount ?: 0
                                if (retryPolicy.shouldRetry(currentAttempt, error)) {
                                    val nextAttempt = currentAttempt + 1
                                    downloadDao.updateRetryCount(downloadId, nextAttempt)
                                    val delayMs = retryPolicy.getBackoffDelayMillis(currentAttempt, error)
                                    queueScope.launch {
                                        delay(delayMs)
                                        queueMutex.withLock {
                                            val latest = downloadDao.getById(downloadId)
                                            if (latest?.status == DownloadStatus.FAILED.name) {
                                                downloadDao.updateStatus(downloadId, DownloadStatus.QUEUED.name)
                                            }
                                        }
                                        processNextInQueue()
                                    }
                                }
                            }
                        } catch (_: CancellationException) {
                            // Handled inside worker
                        } finally {
                            activeJobs.remove(downloadId)
                            processNextInQueue()
                        }
                    }

                    activeJobs[downloadId] = job
                }
            }
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

    private fun DownloadTask.toEntity(): DownloadEntity {
        return DownloadEntity(
            id = id,
            url = url,
            platform = platform.name,
            mediaId = mediaId,
            title = title,
            thumbnailUrl = thumbnailUrl,
            status = status.name,
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
