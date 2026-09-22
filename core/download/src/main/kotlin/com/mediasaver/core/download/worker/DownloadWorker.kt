package com.mediasaver.core.download.worker

import android.util.Log
import com.mediasaver.core.common.FileNameSanitizer
import com.mediasaver.core.common.HashUtils
import com.mediasaver.core.common.SecurityUtils
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

    companion object {
        private const val TAG = "DownloadWorker"
    }

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

        // Security: sanitize target filename and ensure whitelisted extension
        val fileObj = File(targetFileName)
        val safeExt = SecurityUtils.normalizeExtension(fileObj.extension, defaultExtension = "mp4")
        val safeBaseName = FileNameSanitizer.sanitize(fileObj.nameWithoutExtension, fallback = "media")
        val sanitizedTargetFileName = "$safeBaseName.$safeExt"

        try {
            Log.d(TAG, "Starting download: id=$downloadId, file=$sanitizedTargetFileName, platform=$platform")
            Log.d(TAG, "Video stream URL: ${videoStream.url.take(100)}...")
            if (audioStream != null) Log.d(TAG, "Audio stream URL: ${audioStream.url.take(100)}...")

            // Explicit transition: QUEUED -> EXTRACTING
            downloadDao.updateStatus(downloadId, "EXTRACTING")

            // Check maximum allowed download limit (2 GB)
            val estimatedBytes = (videoStream.fileSize ?: 0L) + (audioStream?.fileSize ?: 0L)
            if (estimatedBytes > SecurityUtils.MAX_DOWNLOAD_SIZE_BYTES) {
                throw AppError.NetworkError(null, "Media size exceeds maximum allowed limit of 2GB.")
            }

            // Check storage headroom before streaming
            if (estimatedBytes > 0L && !tempFileManager.hasAvailableStorage(estimatedBytes)) {
                throw AppError.StorageFull(estimatedBytes)
            }

            // Explicit transition: EXTRACTING -> DOWNLOADING
            downloadDao.updateStatus(downloadId, "DOWNLOADING")

            // 1. Download primary/video stream
            downloadStreamToFile(downloadId, videoStream.url, tempVideoFile, platform)

            // 2. If adaptive stream (separate audio track), download audio and mux
            val finalLocalFile = if (audioStream != null && tempAudioFile != null) {
                downloadStreamToFile(downloadId, audioStream.url, tempAudioFile, platform)

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
                fileName = sanitizedTargetFileName,
                platform = platform
            ).getOrThrow()

            // 5. Clean up temporary working file
            val totalFileSize = finalLocalFile.length()
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
                    bytesDownloaded = totalFileSize,
                    totalBytes = totalFileSize,
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
        destinationFile: File,
        platform: PlatformId? = null
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "downloadStreamToFile: id=$downloadId, url=${streamUrl.take(120)}")

        val requestBuilder = Request.Builder()
            .url(streamUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")

        // Add platform-specific headers for CDN access
        if (platform == PlatformId.INSTAGRAM || streamUrl.contains("instagram.com") || streamUrl.contains("cdninstagram") || streamUrl.contains("fbcdn.net")) {
            requestBuilder.header("Referer", "https://www.instagram.com/")
            requestBuilder.header("Origin", "https://www.instagram.com")
        }
        if (platform == PlatformId.YOUTUBE || streamUrl.contains("googlevideo.com") || streamUrl.contains("youtube.com")) {
            requestBuilder.header("Referer", "https://www.youtube.com/")
            requestBuilder.header("Origin", "https://www.youtube.com")
        }
        if (platform == PlatformId.TIKTOK || streamUrl.contains("tiktokcdn.com") || streamUrl.contains("tiktok.com") || streamUrl.contains("tikwm.com")) {
            requestBuilder.header("Referer", "https://www.tiktok.com/")
            requestBuilder.header("Origin", "https://www.tiktok.com")
        }

        val request = requestBuilder.build()

        val response = okHttpClient.newCall(request).execute()
        response.use { resp ->
            Log.d(TAG, "downloadStreamToFile response: code=${resp.code}, contentType=${resp.body?.contentType()}")

            if (!resp.isSuccessful) {
                Log.e(TAG, "Download failed with HTTP ${resp.code} for $downloadId")
                throw AppError.DownloadFailed(resp.code)
            }

            val body = resp.body ?: throw AppError.DownloadFailed(null)
            val contentType = body.contentType()?.toString()
            // Only reject explicitly dangerous MIME types; CDNs sometimes return non-standard types
            if (contentType != null && !SecurityUtils.isAllowedMimeType(contentType)) {
                Log.w(TAG, "Rejected Content-Type '$contentType' for $downloadId - checking if it's a CDN response")
                // Allow binary/octet-stream responses from known CDN domains
                val isCdnUrl = streamUrl.contains("googlevideo.com") || streamUrl.contains("fbcdn.net") ||
                    streamUrl.contains("cdninstagram") || streamUrl.contains("instagram.com") ||
                    streamUrl.contains("tiktokcdn.com") || streamUrl.contains("tikwm.com") ||
                    platform != null
                if (!isCdnUrl) {
                    throw AppError.NetworkError(null, "Prohibited or dangerous media Content-Type: '$contentType'")
                }
                Log.d(TAG, "Allowing CDN response despite Content-Type '$contentType'")
            }

            val totalBytes = body.contentLength().takeIf { it > 0 }
            if (totalBytes != null && totalBytes > SecurityUtils.MAX_DOWNLOAD_SIZE_BYTES) {
                throw AppError.NetworkError(null, "Media file size ($totalBytes bytes) exceeds maximum allowed limit of 2GB.")
            }

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

                        downloadedBytes += bytesRead
                        if (downloadedBytes > SecurityUtils.MAX_DOWNLOAD_SIZE_BYTES) {
                            throw AppError.NetworkError(null, "Download stream exceeded maximum allowed limit of 2GB.")
                        }

                        output.write(buffer, 0, bytesRead)
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
}

