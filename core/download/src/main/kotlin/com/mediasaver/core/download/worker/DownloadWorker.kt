package com.mediasaver.core.download.worker

import com.mediasaver.core.common.HashUtils
import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.download.muxing.MuxingEngine
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import com.mediasaver.core.storage.MediaStoreManager
import com.mediasaver.core.storage.TempFileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of active download progress, transfer speed, and estimated time remaining.
 */
data class DownloadProgress(
    val downloadId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val progressPercent: Int,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long? = null,
    val isComplete: Boolean = false
)

/**
 * Worker executing chunked HTTP streaming downloads via OkHttp directly to disk.
 * Respects coroutine cancellation, enforces storage boundaries, and persists state to Room.
 */
@Singleton
class DownloadWorker @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val tempFileManager: TempFileManager,
    private val mediaStoreManager: MediaStoreManager,
    private val muxingEngine: MuxingEngine
) {

    private val _progressFlow = MutableSharedFlow<DownloadProgress>(replay = 1)
    val progressFlow: Flow<DownloadProgress> = _progressFlow.asSharedFlow()

    /**
     * Executes a complete download lifecycle for a task.
     */
    suspend fun executeDownload(
        downloadId: String,
        targetFileName: String,
        platform: PlatformId,
        videoStream: StreamInfo,
        audioStream: StreamInfo? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val tempFile = tempFileManager.createTempDownloadFile(downloadId)
        val tempVideoFile = if (audioStream != null) tempFileManager.createTempDownloadFile(downloadId, "_vid.tmp") else tempFile
        val tempAudioFile = if (audioStream != null) tempFileManager.createTempDownloadFile(downloadId, "_aud.tmp") else null

        try {
            // Explicit transition: QUEUED -> EXTRACTING
            downloadDao.updateStatus(downloadId, "EXTRACTING")

            // Check storage headroom before streaming
            val estimatedBytes = (videoStream.fileSize ?: 0L) + (audioStream?.fileSize ?: 0L)
            if (estimatedBytes > 0L && !tempFileManager.hasAvailableStorage(estimatedBytes)) {
                throw AppError.StorageFull(estimatedBytes)
            }

            // Explicit transition: EXTRACTING -> DOWNLOADING
            downloadDao.updateStatus(downloadId, "DOWNLOADING")

            // 1. Download primary/video stream
            downloadStreamToFile(downloadId, videoStream.url, tempVideoFile)

            // 2. If adaptive stream (separate audio track), download audio and mux
            val finalLocalFile = if (audioStream != null && tempAudioFile != null) {
                downloadStreamToFile(downloadId, audioStream.url, tempAudioFile)

                // Explicit transition: DOWNLOADING -> MUXING
                downloadDao.updateStatus(downloadId, "MUXING")
                val muxResult = muxingEngine.muxAudioVideo(tempVideoFile, tempAudioFile, tempFile)
                if (muxResult.isFailure) {
                    throw muxResult.exceptionOrNull() ?: AppError.MuxingFailed()
                }
                muxResult.getOrThrow()
            } else {
                tempVideoFile
            }

            // 3. Verify file integrity and calculate SHA-256 hash
            val fileHash = HashUtils.sha256(finalLocalFile)

            // 4. Atomic move to shared MediaStore gallery
            val mediaStoreUri = mediaStoreManager.saveVideo(
                sourceFile = finalLocalFile,
                fileName = targetFileName,
                platform = platform
            ).getOrThrow()

            // 5. Clean up temporary working file
            tempFileManager.deleteTempFile(finalLocalFile)

            // 6. Explicit transition: MUXING/DOWNLOADING -> COMPLETED
            val uriString = mediaStoreUri.toString()
            downloadDao.markCompleted(
                id = downloadId,
                filePath = uriString,
                fileHash = fileHash
            )

            _progressFlow.emit(
                DownloadProgress(
                    downloadId = downloadId,
                    bytesDownloaded = finalLocalFile.length(),
                    totalBytes = finalLocalFile.length(),
                    progressPercent = 100,
                    isComplete = true
                )
            )

            Result.success(uriString)
        } catch (e: CancellationException) {
            // Clean up temporary files on cancellation
            tempFileManager.deleteTempFile(tempFile)
            tempFileManager.deleteTempFile(tempVideoFile)
            tempFileManager.deleteTempFile(tempAudioFile)
            downloadDao.markCancelled(downloadId)
            throw e
        } catch (e: Exception) {
            tempFileManager.deleteTempFile(tempFile)
            tempFileManager.deleteTempFile(tempVideoFile)
            tempFileManager.deleteTempFile(tempAudioFile)

            val errorType = e.javaClass.simpleName
            val errorMessage = e.message ?: "Download failed"
            downloadDao.markFailed(downloadId, errorType, errorMessage)
            Result.failure(e)
        }
    }

    private suspend fun downloadStreamToFile(
        downloadId: String,
        streamUrl: String,
        destinationFile: File
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            .build()

        val response = okHttpClient.newCall(request).execute()
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw AppError.DownloadFailed(resp.code)
            }

            val body = resp.body ?: throw AppError.DownloadFailed(null)
            val totalBytes = body.contentLength().takeIf { it > 0 }

            // Check storage capacity
            if (totalBytes != null && !tempFileManager.hasAvailableStorage(totalBytes)) {
                throw AppError.StorageFull(totalBytes)
            }

            var downloadedBytes = 0L
            var lastDbUpdate = System.currentTimeMillis()
            var speedStartTime = System.currentTimeMillis()
            var speedBytesSample = 0L
            var currentSpeed = 0L

            destinationFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        coroutineContext.ensureActive() // Instant coroutine cancellation check

                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    speedBytesSample += bytesRead

                    val now = System.currentTimeMillis()

                    // Calculate speed and ETA every 500ms
                    if (now - speedStartTime >= 500) {
                        val durationSec = (now - speedStartTime) / 1000.0
                        if (durationSec > 0) {
                            currentSpeed = (speedBytesSample / durationSec).toLong()
                        }
                        speedStartTime = now
                        speedBytesSample = 0L
                    }

                    // Throttle database and Flow updates to avoid main thread UI churn
                    if (now - lastDbUpdate >= 300) {
                        val percent = if (totalBytes != null && totalBytes > 0) {
                            ((downloadedBytes.toDouble() / totalBytes) * 100).toInt()
                        } else {
                            0
                        }

                        val etaSeconds = if (totalBytes != null && currentSpeed > 0) {
                            ((totalBytes - downloadedBytes) / currentSpeed).coerceAtLeast(0L)
                        } else {
                            null
                        }

                        downloadDao.updateProgress(downloadId, downloadedBytes, totalBytes)
                        _progressFlow.emit(
                            DownloadProgress(
                                downloadId = downloadId,
                                bytesDownloaded = downloadedBytes,
                                totalBytes = totalBytes,
                                progressPercent = percent,
                                speedBytesPerSec = currentSpeed,
                                etaSeconds = etaSeconds
                            )
                        )
                        lastDbUpdate = now
                    }
                }
            }
        }
    }
}
