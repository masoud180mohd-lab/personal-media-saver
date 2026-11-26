package com.mediasaver.core.download.queue

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.database.dao.StreamOptionDao
import com.mediasaver.core.database.entity.DownloadEntity
import com.mediasaver.core.database.entity.StreamOptionEntity
import com.mediasaver.core.domain.repository.SettingsRepository
import com.mediasaver.core.download.retry.RetryPolicy
import com.mediasaver.core.download.service.DownloadService
import com.mediasaver.core.download.worker.DownloadProgress
import com.mediasaver.core.download.worker.DownloadWorker
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
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
class DownloadQueueManager(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val streamOptionDao: StreamOptionDao,
    private val downloadWorker: DownloadWorker,
    private val tempFileManager: TempFileManager,
    private val retryPolicy: RetryPolicy,
    private val settingsRepository: SettingsRepository? = null,
    private val isWifiConnectedProvider: () -> Boolean = { isWifiOrUnmeteredConnected(context) }
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        downloadDao: DownloadDao,
        streamOptionDao: StreamOptionDao,
        downloadWorker: DownloadWorker,
        tempFileManager: TempFileManager,
        retryPolicy: RetryPolicy,
        settingsRepository: SettingsRepository
    ) : this(
        context = context,
        downloadDao = downloadDao,
        streamOptionDao = streamOptionDao,
        downloadWorker = downloadWorker,
        tempFileManager = tempFileManager,
        retryPolicy = retryPolicy,
        settingsRepository = settingsRepository,
        isWifiConnectedProvider = { isWifiOrUnmeteredConnected(context) }
    )

    private val queueScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queueMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    var maxConcurrentDownloads: Int = 3

    @Volatile
    var wifiOnlyDownload: Boolean = false

    private var recovered = false
    private val duplicates = com.mediasaver.core.download.duplicate.DuplicateDetector(downloadDao)

    init {
        settingsRepository?.let { repo ->
            queueScope.launch {
                repo.settings.collect { appSettings ->
                    val previousLimit = maxConcurrentDownloads
                    val previousWifiOnly = wifiOnlyDownload
                    maxConcurrentDownloads = appSettings.maxConcurrentDownloads.coerceIn(1, 5)
                    wifiOnlyDownload = appSettings.wifiOnlyDownload
                    if (maxConcurrentDownloads > previousLimit || (previousWifiOnly && !wifiOnlyDownload)) {
                        processNextInQueue()
                    }
                }
            }
        }
    }

    private suspend fun syncSettingsIfAvailable() {
        val current = settingsRepository?.getSettings() ?: return
        maxConcurrentDownloads = current.maxConcurrentDownloads.coerceIn(1, 5)
        wifiOnlyDownload = current.wifiOnlyDownload
    }

    private suspend fun ensureNetworkAllowed() {
        syncSettingsIfAvailable()
        if (wifiOnlyDownload && !isWifiConnectedProvider()) {
            throw AppError.NetworkError(userMessage = WIFI_ONLY_MESSAGE)
        }
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
        ensureNetworkAllowed()
        performStartupRecovery()
        queueMutex.withLock {
            downloadWorker.clearPauseRequest(task.id)
            val itemId = when {
                videoStream.mediaIndex != null -> "${task.mediaId}#slide_${videoStream.mediaIndex!! + 1}"
                videoStream.isAudioOnly -> "${task.mediaId}#audio"
                else -> task.mediaId
            }
            val existing = duplicates.checkBeforeDownload(task.url, task.platform, itemId)
            if (existing.isDuplicate) throw AppError.DuplicateDownload()
            val entity = task.toEntity().copy(mediaId = itemId, status = DownloadStatus.QUEUED.name)

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
                    isAudioOnly = videoStream.isAudioOnly,
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

            streamOptionDao.enqueue(entity, streamEntities)
        }

        DownloadService.start(context)
        processNextInQueue()
    }

    /**
     * Pauses an active or queued download while keeping partial temp files for HTTP Range resume.
     */
    suspend fun pause(downloadId: String) {
        queueMutex.withLock {
            val task = downloadDao.getById(downloadId) ?: return
            if (task.status !in setOf("QUEUED", "EXTRACTING", "DOWNLOADING")) return
            downloadWorker.markPauseRequested(downloadId)
            downloadDao.markPaused(downloadId)
            activeJobs.remove(downloadId)?.cancel()
        }
        processNextInQueue()
    }

    /**
     * Resumes a paused download task from any saved partial file on disk.
     */
    suspend fun resume(downloadId: String) {
        ensureNetworkAllowed()
        performStartupRecovery()
        queueMutex.withLock {
            val task = downloadDao.getById(downloadId) ?: return
            if (task.status != DownloadStatus.PAUSED.name) return
            downloadWorker.clearPauseRequest(downloadId)
            downloadDao.updateStatus(downloadId, DownloadStatus.QUEUED.name)
        }
        DownloadService.start(context)
        processNextInQueue()
    }

    /**
     * Pauses all queued, extracting, or downloading tasks without deleting partial temp files.
     */
    suspend fun pauseAll() {
        queueMutex.withLock {
            val pausable = downloadDao.findActiveDownloads()
                .filter { it.status in setOf("QUEUED", "EXTRACTING", "DOWNLOADING") }
            for (task in pausable) {
                downloadWorker.markPauseRequested(task.id)
                downloadDao.markPaused(task.id)
            }
            for (task in pausable) {
                activeJobs.remove(task.id)?.cancel()
            }
        }
    }

    /**
     * Re-queues all paused download tasks.
     */
    suspend fun resumeAll() {
        ensureNetworkAllowed()
        performStartupRecovery()
        val hasResumed = queueMutex.withLock {
            val paused = downloadDao.findPausedDownloads()
            if (paused.isEmpty()) return@withLock false
            for (task in paused) {
                downloadWorker.clearPauseRequest(task.id)
                downloadDao.updateStatus(task.id, DownloadStatus.QUEUED.name)
            }
            true
        }
        if (hasResumed) {
            DownloadService.start(context)
            processNextInQueue()
        }
    }

    /**
     * Resets retry counters and re-queues all failed download tasks.
     */
    suspend fun retryAllFailed() {
        ensureNetworkAllowed()
        performStartupRecovery()
        val hasRetried = queueMutex.withLock {
            val failed = downloadDao.findFailedDownloads()
            if (failed.isEmpty()) return@withLock false
            for (task in failed) {
                downloadWorker.clearPauseRequest(task.id)
                downloadDao.updateRetryCount(task.id, 0)
                downloadDao.updateStatus(task.id, DownloadStatus.QUEUED.name)
            }
            true
        }
        if (hasRetried) {
            DownloadService.start(context)
            processNextInQueue()
        }
    }

    /**
     * Cancels all active, queued, and paused downloads and removes their temporary files.
     */
    suspend fun cancelAll() {
        queueMutex.withLock {
            val targets = downloadDao.findActiveDownloads() + downloadDao.findPausedDownloads()
            for (task in targets) {
                downloadWorker.clearPauseRequest(task.id)
                downloadDao.markCancelled(task.id)
                activeJobs.remove(task.id)?.cancel()
                tempFileManager.deleteAllTempFiles(task.id)
            }
        }
    }

    /**
     * Cancels an active or queued download.
     */
    suspend fun cancel(downloadId: String) {
        downloadWorker.clearPauseRequest(downloadId)
        val job = activeJobs.remove(downloadId)
        job?.cancel()

        downloadDao.markCancelled(downloadId)
        tempFileManager.deleteAllTempFiles(downloadId)
        processNextInQueue()
    }

    /**
     * Cancels any running job, cleans all temporary files, and removes the database record.
     */
    suspend fun delete(downloadId: String) {
        downloadWorker.clearPauseRequest(downloadId)
        activeJobs.remove(downloadId)?.cancel()
        tempFileManager.deleteAllTempFiles(downloadId)
        downloadDao.deleteById(downloadId)
        processNextInQueue()
    }

    /**
     * Re-queues a failed or cancelled download task.
     * Manual retry is always permitted and resets the retry counter.
     */
    suspend fun retry(downloadId: String) {
        ensureNetworkAllowed()
        performStartupRecovery()
        queueMutex.withLock {
            downloadWorker.clearPauseRequest(downloadId)
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
    suspend fun performStartupRecovery() {
        val hasActive = queueMutex.withLock {
            if (recovered) return
            val active = downloadDao.findActiveDownloads()
            for (task in active) {
                if (task.id !in activeJobs.keys && task.status in setOf("EXTRACTING", "DOWNLOADING", "MUXING")) {
                    if (task.retryCount >= 3) downloadDao.markFailed(task.id, "Interrupted", "Retry limit reached.")
                    else downloadDao.updateStatus(task.id, "QUEUED")
                }
            }
            val activeAfter = downloadDao.findActiveDownloads()
            val pausedIds = downloadDao.findPausedDownloads().mapTo(mutableSetOf()) { it.id }
            val protected = activeAfter.mapTo(mutableSetOf()) { it.id } + pausedIds + activeJobs.keys
            tempFileManager.cleanOrphanedTempFiles(protected)
            val shouldRunService = activeAfter.isNotEmpty() || activeJobs.isNotEmpty()
            if (shouldRunService) DownloadService.start(context)
            recovered = true
            shouldRunService
        }
        if (hasActive) processNextInQueue()
    }

    /**
     * Schedules the next eligible queued tasks up to the concurrency limit.
     */
    private fun processNextInQueue() {
        queueScope.launch {
            queueMutex.withLock {
                if (!recovered) return@withLock
                if (wifiOnlyDownload && !isWifiConnectedProvider()) return@withLock
                val currentRunning = activeJobs.size
                if (currentRunning >= maxConcurrentDownloads) return@withLock

                val capacity = maxConcurrentDownloads - currentRunning
                val activeDownloads = downloadDao.findActiveDownloads()
                val queuedTasks = activeDownloads.filter { it.status == DownloadStatus.QUEUED.name && it.id !in activeJobs.keys }
                    .take(capacity)

                for (taskEntity in queuedTasks) {
                    val downloadId = taskEntity.id
                    if (activeJobs.containsKey(downloadId)) continue

                    val streams = streamOptionDao.getByDownloadId(downloadId)
                    val videoStreamEntity = streams.firstOrNull { it.id == "${downloadId}_video" } ?: streams.firstOrNull { !it.isAudioOnly } ?: streams.singleOrNull()
                    val audioStreamEntity = streams.firstOrNull { it.isAudioOnly && it.id != videoStreamEntity?.id }

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
                        isAudioOnly = videoStreamEntity.isAudioOnly,
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

                    val job = queueScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        try {
                            val result = downloadWorker.executeDownload(
                                downloadId = downloadId,
                                targetFileName = targetFileName,
                                platform = platform,
                                videoStream = videoStream,
                                audioStream = audioStream,
                                subfolder = decodeSubfolder(taskEntity.description)
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
                    job.start()
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
            durationSeconds = durationSeconds,
            errorMessage = errorMessage,
            subfolder = decodeSubfolder(description)
        )
    }

    private fun DownloadTask.toEntity(): DownloadEntity {
        return DownloadEntity(
            id = id,
            url = url,
            platform = platform.name,
            mediaId = mediaId,
            title = title,
            description = encodeSubfolder(subfolder),
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
            durationSeconds = durationSeconds,
            errorMessage = errorMessage
        )
    }

    companion object {
        internal const val WIFI_ONLY_MESSAGE =
            "Wi-Fi Only mode is enabled. Connect to Wi-Fi or disable Wi-Fi Only in Settings."

        internal const val SUBFOLDER_PREFIX = "folder:"

        internal fun encodeSubfolder(subfolder: String?): String? {
            return subfolder?.trim()?.takeIf { it.isNotBlank() }?.let { "$SUBFOLDER_PREFIX$it" }
        }

        internal fun decodeSubfolder(description: String?): String? {
            return description
                ?.takeIf { it.startsWith(SUBFOLDER_PREFIX) }
                ?.removePrefix(SUBFOLDER_PREFIX)
                ?.takeIf { it.isNotBlank() }
        }

        internal fun isWifiOrUnmeteredConnected(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
    }
}
