package com.mediasaver.core.download.worker

import com.mediasaver.core.network.EphemeralStreamHeaders
import com.mediasaver.core.network.interceptor.PlatformWebDomain
import com.mediasaver.core.common.FileNameSanitizer
import com.mediasaver.core.download.duplicate.DuplicateDetector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import com.mediasaver.core.common.SecurityUtils
import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.download.metadata.CoverArtFetcher
import com.mediasaver.core.download.metadata.Mp4MetadataTagger
import com.mediasaver.core.download.muxing.MuxingEngine
import com.mediasaver.core.model.AudioMetadata
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
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
    private val muxingEngine: MuxingEngine,
    private val ephemeralHeaders: EphemeralStreamHeaders,
    private val coverArtFetcher: CoverArtFetcher
) {
    constructor(
        okHttpClient: OkHttpClient,
        downloadDao: DownloadDao,
        tempFileManager: TempFileManager,
        mediaStoreManager: MediaStoreManager,
        muxingEngine: MuxingEngine,
        ephemeralHeaders: EphemeralStreamHeaders = EphemeralStreamHeaders()
    ) : this(
        okHttpClient = okHttpClient,
        downloadDao = downloadDao,
        tempFileManager = tempFileManager,
        mediaStoreManager = mediaStoreManager,
        muxingEngine = muxingEngine,
        ephemeralHeaders = ephemeralHeaders,
        coverArtFetcher = CoverArtFetcher(okHttpClient)
    )

    private val duplicates = DuplicateDetector(downloadDao)
    private val publicationMutex = Mutex()
    private val pauseRequestedIds = ConcurrentHashMap.newKeySet<String>()

    private val _progressFlow = MutableSharedFlow<DownloadProgress>(replay = 1)
    val progressFlow: Flow<DownloadProgress> = _progressFlow.asSharedFlow()

    fun markPauseRequested(downloadId: String) {
        pauseRequestedIds.add(downloadId)
    }

    fun clearPauseRequest(downloadId: String) {
        pauseRequestedIds.remove(downloadId)
    }

    /**
     * Executes a complete download lifecycle for a task.
     */
    suspend fun executeDownload(
        downloadId: String,
        targetFileName: String,
        platform: PlatformId,
        videoStream: StreamInfo,
        audioStream: StreamInfo? = null,
        subfolder: String? = null,
        audioMetadata: AudioMetadata? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val tempFile = tempFileManager.createTempDownloadFile(downloadId)
        val tempVideoFile = if (audioStream != null) tempFileManager.createTempDownloadFile(downloadId, "_vid.tmp") else tempFile
        val tempAudioFile = if (audioStream != null) tempFileManager.createTempDownloadFile(downloadId, "_aud.tmp") else null
        var taggedTempFile: File? = null

        // Security: sanitize target filename and ensure whitelisted extension
        val fileObj = File(targetFileName)
        val safeBaseName = FileNameSanitizer.sanitize(fileObj.nameWithoutExtension, fallback = "media")

        try {
            if (videoStream.isVideoOnly && audioStream == null) throw AppError.MuxingFailed()

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
            val primaryKind = downloadStreamToFile(downloadId, videoStream, tempVideoFile, platform)

            // 2. If adaptive stream (separate audio track), download audio and mux
            val finalLocalFile = if (audioStream != null && tempAudioFile != null) {
                downloadStreamToFile(downloadId, audioStream, tempAudioFile, platform)

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

            val kind = if (audioStream == null) primaryKind else finalLocalFile.inputStream().use { input ->
                val header = ByteArray(12)
                java.io.DataInputStream(input).readFully(header)
                MediaPayload.verify(null, header, videoStream)
            }
            val publishFile = if (kind == MediaPayload.M4A && audioMetadata?.hasAnyTag == true) {
                val coverArt = try {
                    audioMetadata.coverArtUrl?.let { coverArtFetcher.fetch(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val candidate = tempFileManager.createTempDownloadFile(downloadId, "_tagged.tmp")
                taggedTempFile = candidate
                if (Mp4MetadataTagger.tag(finalLocalFile, candidate, audioMetadata, coverArt) &&
                    candidate.length() > 0L
                ) {
                    candidate
                } else {
                    tempFileManager.deleteTempFile(candidate)
                    taggedTempFile = null
                    finalLocalFile
                }
            } else {
                finalLocalFile
            }
            val name = "$safeBaseName.${kind.extension}"
            val totalFileSize = publishFile.length()
            val duplicate = duplicates.checkAfterDownload(publishFile)
            val hash = requireNotNull(duplicate.fileHash)
            val uriString = publicationMutex.withLock {
                coroutineContext.ensureActive()
                // Recheck under the lock: a concurrent transfer may just have published the same bytes.
                withContext(NonCancellable) {
                    val existing = duplicate.existingTask?.filePath ?: downloadDao.findByFileHash(hash)?.filePath
                    val created = if (existing != null) null else when {
                        kind.mime.startsWith("image/") -> mediaStoreManager.saveImage(publishFile, name, platform, kind.mime, subfolder = subfolder)
                        kind.mime.startsWith("audio/") -> mediaStoreManager.saveAudio(publishFile, name, platform, kind.mime, subfolder = subfolder, metadata = audioMetadata)
                        else -> mediaStoreManager.saveVideo(publishFile, name, platform, kind.mime, subfolder = subfolder)
                    }.getOrThrow()
                    val path = existing ?: requireNotNull(created).toString()
                    try {
                        downloadDao.completeWithProgress(downloadId, path, hash, totalFileSize, kind.mime)
                    } catch (e: Exception) {
                        if (created != null) mediaStoreManager.discard(created)
                        throw e
                    }
                    path
                }
            }
            tempFileManager.deleteTempFile(taggedTempFile)
            tempFileManager.deleteTempFile(finalLocalFile)

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
            val isPaused = withContext(NonCancellable) {
                pauseRequestedIds.remove(downloadId) || downloadDao.getById(downloadId)?.status == "PAUSED"
            }
            if (isPaused) {
                withContext(NonCancellable) {
                    val current = downloadDao.getById(downloadId)
                    if (current != null && current.status != "COMPLETED") {
                        val persistedBytes = if (audioStream != null) {
                            (tempVideoFile.takeIf { it.exists() }?.length() ?: 0L) +
                                (tempAudioFile?.takeIf { it.exists() }?.length() ?: 0L)
                        } else {
                            tempVideoFile.takeIf { it.exists() }?.length() ?: 0L
                        }
                        if (persistedBytes > 0L) {
                            downloadDao.updateProgress(downloadId, persistedBytes, current.totalBytes)
                        }
                        downloadDao.markPaused(downloadId)
                    }
                }
                throw e
            }
            // Clean up temporary files on cancellation
            tempFileManager.deleteTempFile(taggedTempFile)
            tempFileManager.deleteTempFile(tempFile)
            tempFileManager.deleteTempFile(tempVideoFile)
            tempFileManager.deleteTempFile(tempAudioFile)
            withContext(NonCancellable) {
                if (downloadDao.getById(downloadId)?.status != "COMPLETED") downloadDao.markCancelled(downloadId)
            }
            throw e
        } catch (e: Exception) {
            pauseRequestedIds.remove(downloadId)
            tempFileManager.deleteTempFile(taggedTempFile)
            tempFileManager.deleteTempFile(tempFile)
            tempFileManager.deleteTempFile(tempVideoFile)
            tempFileManager.deleteTempFile(tempAudioFile)

            val errorType = e.javaClass.simpleName
            val errorMessage = e.message ?: "Download failed"
            downloadDao.markFailed(downloadId, errorType, errorMessage)
            Result.failure(e)
        }
    }

    private fun buildRequest(streamUrl: String, platform: PlatformId, rangeHeader: String? = null): Request {
        val (origin, hosts) = when (platform) {
            PlatformId.INSTAGRAM -> "https://www.instagram.com" to PlatformWebDomain("instagram.com", "cdninstagram.com", "fbcdn.net")
            PlatformId.FACEBOOK -> "https://www.facebook.com" to PlatformWebDomain("facebook.com", "fb.watch", "fbcdn.net")
            PlatformId.TWITTER -> "https://x.com" to PlatformWebDomain("x.com", "twitter.com", "t.co", "twimg.com")
            PlatformId.YOUTUBE -> "https://www.youtube.com" to PlatformWebDomain("youtube.com", "googlevideo.com")
            PlatformId.TIKTOK -> "https://www.tiktok.com" to PlatformWebDomain(
                "tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com",
                "tiktokcdn-eu.com", "tiktokv.com", "byteoversea.com", "ibytedtos.com", "muscdn.com"
            )
            else -> throw AppError.UnsupportedPlatform(platform.name)
        }
        return Request.Builder()
            .url(streamUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "$origin/")
            .header("Origin", origin)
            .apply {
                if (rangeHeader != null) header("Range", rangeHeader)
                for ((k, v) in ephemeralHeaders.get(streamUrl)) header(k, v)
            }
            .tag(PlatformWebDomain::class.java, hosts)
            .build()
    }

    private suspend fun emitTransferProgress(
        downloadId: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        currentSpeed: Long
    ) {
        val percent = if (totalBytes != null && totalBytes > 0) {
            ((downloadedBytes.toDouble() / totalBytes) * 100).toInt().coerceIn(0, 100)
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
    }

    private data class PartialFileInfo(
        val length: Long,
        val headerBytes: ByteArray,
        val payload: MediaPayload
    )

    private fun inspectExistingPartialFile(file: File, stream: StreamInfo): PartialFileInfo? {
        if (!file.exists() || file.length() < 12L) return null
        val header = ByteArray(12)
        val readCount = runCatching {
            FileInputStream(file).use { input ->
                var totalRead = 0
                while (totalRead < header.size) {
                    val read = input.read(header, totalRead, header.size - totalRead)
                    if (read <= 0) break
                    totalRead += read
                }
                totalRead
            }
        }.getOrNull() ?: return null
        if (readCount < 12) return null
        val payload = runCatching { MediaPayload.verify(stream.mimeType, header, stream) }.getOrNull() ?: return null
        return PartialFileInfo(length = file.length(), headerBytes = header, payload = payload)
    }

    private suspend fun downloadStreamToFile(
        downloadId: String,
        stream: StreamInfo,
        destinationFile: File,
        platform: PlatformId
    ): MediaPayload = withContext(Dispatchers.IO) {
        val streamUrl = stream.url
        if (platform == PlatformId.YOUTUBE &&
            streamUrl.toHttpUrlOrNull()?.queryParameter("c")?.equals("VISIONOS", ignoreCase = true) == true
        ) {
            return@withContext downloadChunkedRange(downloadId, stream, destinationFile, platform)
        }

        var partial = inspectExistingPartialFile(destinationFile, stream)
        val expectedSize = stream.fileSize
        if (partial != null && expectedSize != null && expectedSize > 0L && partial.length == expectedSize) {
            return@withContext partial.payload
        }

        var fallbackChunked = false
        var directKind: MediaPayload? = null

        for (attempt in 0..1) {
            val existingBytes = partial?.length ?: 0L
            val request = if (existingBytes > 0L) {
                buildRequest(streamUrl, platform, "bytes=$existingBytes-")
            } else {
                buildRequest(streamUrl, platform)
            }
            var retryFromZero = false

            okHttpClient.newCall(request).execute().use { resp ->
                if (platform == PlatformId.YOUTUBE && resp.code == 403) {
                    fallbackChunked = true
                    return@use
                }

                if (existingBytes > 0L && resp.code == 416) {
                    val totalFrom416 = resp.header("Content-Range")
                        ?.substringAfter('/')
                        ?.trim()
                        ?.toLongOrNull()
                    if (totalFrom416 != null && totalFrom416 == existingBytes) {
                        directKind = partial!!.payload
                        return@use
                    }
                    destinationFile.delete()
                    partial = null
                    retryFromZero = true
                    return@use
                }

                if (!resp.isSuccessful) {
                    throw AppError.DownloadFailed(resp.code)
                }

                val body = resp.body ?: throw AppError.DownloadFailed(null)
                val contentRange = resp.header("Content-Range")
                val isExpectedPartial = existingBytes > 0L &&
                    resp.code == 206 &&
                    contentRange?.lowercase()?.startsWith("bytes $existingBytes-") == true

                val kind: MediaPayload
                val totalBytes: Long?
                val startOffset: Long
                val appendMode: Boolean

                if (isExpectedPartial) {
                    kind = MediaPayload.verify(resp.header("Content-Type"), partial!!.headerBytes, stream)
                    val totalFromRange = contentRange?.substringAfter('/')?.trim()?.toLongOrNull()?.takeIf { it > 0L }
                    val remainingLen = body.contentLength().takeIf { it > 0L }
                    totalBytes = totalFromRange ?: remainingLen?.let { existingBytes + it }
                    if (totalBytes != null && totalBytes > SecurityUtils.MAX_DOWNLOAD_SIZE_BYTES) {
                        throw AppError.NetworkError(null, "Media file size ($totalBytes bytes) exceeds maximum allowed limit of 2GB.")
                    }
                    val neededBytes = remainingLen ?: totalBytes?.let { (it - existingBytes).coerceAtLeast(0L) }
                    if (neededBytes != null && neededBytes > 0L && !tempFileManager.hasAvailableStorage(neededBytes)) {
                        throw AppError.StorageFull(neededBytes)
                    }
                    startOffset = existingBytes
                    appendMode = true
                    emitTransferProgress(downloadId, startOffset, totalBytes, 0L)
                } else {
                    val source = body.source()
                    source.request(12L)
                    val header = source.peek().readByteArray(minOf(12L, source.buffer.size))
                    kind = MediaPayload.verify(resp.header("Content-Type"), header, stream)

                    totalBytes = body.contentLength().takeIf { it > 0L }
                    if (totalBytes != null && totalBytes > SecurityUtils.MAX_DOWNLOAD_SIZE_BYTES) {
                        throw AppError.NetworkError(null, "Media file size ($totalBytes bytes) exceeds maximum allowed limit of 2GB.")
                    }
                    if (totalBytes != null && !tempFileManager.hasAvailableStorage(totalBytes)) {
                        throw AppError.StorageFull(totalBytes)
                    }
                    startOffset = 0L
                    appendMode = false
                }

                var downloadedBytes = startOffset
                var lastDbUpdate = System.currentTimeMillis()
                var speedStartTime = System.currentTimeMillis()
                var speedBytesSample = 0L
                var currentSpeed = 0L

                FileOutputStream(destinationFile, appendMode).use { output ->
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
                                emitTransferProgress(downloadId, downloadedBytes, totalBytes, currentSpeed)
                                lastDbUpdate = now
                            }
                        }
                    }
                }
                directKind = kind
            }

            if (fallbackChunked) {
                return@withContext downloadChunkedRange(downloadId, stream, destinationFile, platform)
            }
            if (directKind != null) {
                return@withContext directKind!!
            }
            if (!retryFromZero) break
        }

        requireNotNull(directKind)
    }

    private suspend fun downloadChunkedRange(
        downloadId: String,
        stream: StreamInfo,
        destinationFile: File,
        platform: PlatformId
    ): MediaPayload = withContext(Dispatchers.IO) {
        val chunkSize = 1_048_576L
        val clen = stream.url.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()?.takeIf { it > 0 }

        var partial = inspectExistingPartialFile(destinationFile, stream)
        if (partial != null && clen != null && partial.length == clen) {
            return@withContext partial.payload
        }

        for (pass in 0..1) {
            var totalBytes: Long? = clen
            val canResume = partial != null && (clen == null || partial!!.length < clen)
            var offset: Long
            var verifiedKind: MediaPayload?
            val appendMode: Boolean

            if (canResume) {
                offset = partial!!.length
                verifiedKind = partial!!.payload
                appendMode = true
                emitTransferProgress(downloadId, offset, totalBytes, 0L)
            } else {
                if (destinationFile.exists()) destinationFile.delete()
                offset = 0L
                verifiedKind = null
                appendMode = false
            }

            var restartFromZero = false
            var lastDbUpdate = System.currentTimeMillis()
            var speedStartTime = System.currentTimeMillis()
            var speedBytesSample = 0L
            var currentSpeed = 0L

            FileOutputStream(destinationFile, appendMode).use { output ->
                val buffer = ByteArray(8192)
                while (offset < (totalBytes ?: Long.MAX_VALUE)) {
                    coroutineContext.ensureActive()
                    val offsetBeforeChunk = offset
                    val rangeEnd = totalBytes?.let { minOf(offset + chunkSize - 1, it - 1) } ?: (offset + chunkSize - 1)
                    val chunkRequest = buildRequest(stream.url, platform, "bytes=$offset-$rangeEnd")

                    var reachedEnd = false
                    okHttpClient.newCall(chunkRequest).execute().use { resp ->
                        if (resp.code == 416 && offset > 0L) {
                            val totalFrom416 = resp.header("Content-Range")
                                ?.substringAfter('/')
                                ?.trim()
                                ?.toLongOrNull()
                            if (totalBytes == null || (totalFrom416 != null && totalFrom416 == offset && verifiedKind != null)) {
                                reachedEnd = true
                                return@use
                            }
                        }
                        if (platform == PlatformId.YOUTUBE && resp.code == 403) {
                            throw AppError.DownloadFailed(
                                403,
                                userMessage = "YouTube blocked this high-resolution stream (HTTP 403). Try 360p or a shorter video."
                            )
                        }
                        if (!resp.isSuccessful) {
                            throw AppError.DownloadFailed(resp.code)
                        }

                        if (offsetBeforeChunk > 0L && resp.code == 200) {
                            // Server ignored Range header and sent the full stream; restart cleanly from byte 0.
                            restartFromZero = true
                            return@use
                        }

                        val body = resp.body ?: throw AppError.DownloadFailed(null)
                        if (offset == 0L) {
                            val source = body.source()
                            source.request(12L)
                            val header = source.peek().readByteArray(minOf(12L, source.buffer.size))
                            verifiedKind = MediaPayload.verify(resp.header("Content-Type"), header, stream)

                            totalBytes = if (resp.code == 206) {
                                resp.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                                    ?: clen
                            } else {
                                body.contentLength().takeIf { it > 0 } ?: clen
                            }
                            val knownTotal = totalBytes
                            if (knownTotal != null && knownTotal > SecurityUtils.MAX_DOWNLOAD_SIZE_BYTES) {
                                throw AppError.NetworkError(null, "Media file size ($knownTotal bytes) exceeds maximum allowed limit of 2GB.")
                            }
                            if (knownTotal != null && !tempFileManager.hasAvailableStorage(knownTotal)) {
                                throw AppError.StorageFull(knownTotal)
                            }
                        } else if (totalBytes == null && resp.code == 206) {
                            totalBytes = resp.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                        }

                        var bytesThisChunk = 0L
                        body.byteStream().use { input ->
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                coroutineContext.ensureActive()
                                bytesThisChunk += bytesRead
                                offset += bytesRead
                                if (offset > SecurityUtils.MAX_DOWNLOAD_SIZE_BYTES) {
                                    throw AppError.NetworkError(null, "Download stream exceeded maximum allowed limit of 2GB.")
                                }
                                output.write(buffer, 0, bytesRead)
                                speedBytesSample += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - speedStartTime >= 500) {
                                    val durationSec = (now - speedStartTime) / 1000.0
                                    if (durationSec > 0) {
                                        currentSpeed = (speedBytesSample / durationSec).toLong()
                                    }
                                    speedStartTime = now
                                    speedBytesSample = 0L
                                }
                                if (now - lastDbUpdate >= 300) {
                                    emitTransferProgress(downloadId, offset, totalBytes, currentSpeed)
                                    lastDbUpdate = now
                                }
                            }
                        }

                        val expectedChunkLen = rangeEnd - offsetBeforeChunk + 1
                        if (resp.code == 200 || (totalBytes == null && bytesThisChunk < expectedChunkLen)) {
                            reachedEnd = true
                        } else if (bytesThisChunk == 0L) {
                            if (totalBytes != null && offset < totalBytes!!) {
                                throw AppError.DownloadFailed(null)
                            }
                            reachedEnd = true
                        }
                    }
                    if (restartFromZero || reachedEnd) break
                }
            }

            if (restartFromZero && pass == 0) {
                destinationFile.delete()
                partial = null
                continue
            }
            return@withContext verifiedKind ?: throw AppError.DownloadFailed(null)
        }
        throw AppError.DownloadFailed(null)
    }
}
