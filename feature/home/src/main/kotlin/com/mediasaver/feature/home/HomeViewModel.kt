package com.mediasaver.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediasaver.core.common.FileNameSanitizer
import com.mediasaver.core.common.PlatformDetector
import com.mediasaver.core.domain.usecase.EnqueueDownloadUseCase
import com.mediasaver.core.domain.usecase.GetSettingsUseCase
import com.mediasaver.core.domain.usecase.ProcessUrlUseCase
import com.mediasaver.core.domain.usecase.ResolveStreamsUseCase
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.companionAudio
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val processUrlUseCase: ProcessUrlUseCase,
    private val resolveStreamsUseCase: ResolveStreamsUseCase,
    private val enqueueDownloadUseCase: EnqueueDownloadUseCase,
    private val getSettingsUseCase: GetSettingsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var extraction: Job? = null

    fun onUrlChange(newUrl: String) {
        if (newUrl == _uiState.value.urlInput) return
        extraction?.cancel()
        val detected = PlatformDetector.detect(newUrl)
        _uiState.update {
            it.copy(
                urlInput = newUrl,
                mediaInfo = null,
                selectedStream = null,
                availableStreams = emptyList(),
                selectedPlaylistVideoIds = emptySet(),
                playlistEnqueueProgress = null,
                isExtracting = false,
                detectedPlatform = detected,
                errorMessage = null
            )
        }
    }

    fun processUrl(urlToProcess: String? = null) {
        val targetUrl = (urlToProcess ?: _uiState.value.urlInput).trim()
        if (targetUrl.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid media URL.") }
            return
        }

        extraction?.cancel()
        extraction = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExtracting = true,
                    errorMessage = null,
                    mediaInfo = null,
                    availableStreams = emptyList(),
                    selectedStream = null,
                    selectedPlaylistVideoIds = emptySet(),
                    playlistEnqueueProgress = null
                )
            }

            val result = processUrlUseCase(targetUrl)
            result.onSuccess { info ->
                val settings = getSettingsUseCase().firstOrNull()
                val preferredQuality = settings?.preferredQuality ?: "720p"

                if (info.isPlaylist) {
                    _uiState.update {
                        it.copy(
                            isExtracting = false,
                            mediaInfo = info,
                            availableStreams = emptyList(),
                            selectedStream = null,
                            selectedPlaylistVideoIds = info.playlistItems.mapTo(linkedSetOf()) { item -> item.videoId },
                            playlistQuality = preferredQuality,
                            detectedPlatform = info.platform
                        )
                    }
                    return@launch
                }

                // Resolve stream options
                val streamsResult = resolveStreamsUseCase(info.id, targetUrl)
                val streams = streamsResult.getOrNull().orEmpty()
                if (streams.isEmpty()) {
                    _uiState.update { it.copy(isExtracting = false, mediaInfo = info, selectedStream = null,
                        availableStreams = emptyList(), errorMessage = streamsResult.exceptionOrNull()?.localizedMessage
                            ?: "No downloadable streams are available.") }
                    return@launch
                }

                val chosenStream = selectPreferredStream(streams, preferredQuality)

                _uiState.update {
                    it.copy(
                        isExtracting = false,
                        mediaInfo = info,
                        availableStreams = streams,
                        selectedStream = chosenStream,
                        detectedPlatform = info.platform
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isExtracting = false,
                        errorMessage = error.localizedMessage ?: "Failed to extract media information."
                    )
                }
            }
        }
    }

    fun onSelectStream(stream: StreamInfo) {
        _uiState.update { it.copy(selectedStream = stream) }
    }

    fun startDownload(streamToDownload: StreamInfo? = null) {
        val selected = streamToDownload ?: _uiState.value.selectedStream ?: return
        startBatchDownload(listOf(selected))
    }

    fun startBatchDownload(streams: List<StreamInfo>) {
        val state = _uiState.value
        val media = state.mediaInfo ?: return
        if (state.isEnqueuing || streams.isEmpty() || !state.availableStreams.containsAll(streams)) return
        _uiState.update { it.copy(isEnqueuing = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                var enqueuedCount = 0
                var skippedDuplicates = 0
                var firstError: Exception? = null
                for (requested in streams) {
                    try {
                        val audio = if (requested.isVideoOnly) requested.companionAudio(state.availableStreams) else null
                        val primary = if (requested.isVideoOnly && audio == null)
                            state.availableStreams.firstOrNull { it.isProgressive } ?: error("No compatible audio stream is available.")
                            else requested
                        val task = DownloadTask(id = UUID.randomUUID().toString(), url = media.sourceUrl,
                            platform = media.platform, mediaId = media.id, title = media.title, thumbnailUrl = primary.thumbnailUrl ?: media.thumbnailUrl,
                            status = DownloadStatus.QUEUED, selectedQuality = primary.quality, selectedFormat = primary.format,
                            mimeType = primary.mimeType, totalBytes = primary.fileSize, durationSeconds = media.durationSeconds)
                        enqueueDownloadUseCase(task, primary, audio)
                        enqueuedCount++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: AppError.DuplicateDownload) {
                        skippedDuplicates++
                    } catch (e: Exception) {
                        if (streams.size == 1) throw e
                        if (firstError == null) firstError = e
                    }
                }
                when {
                    enqueuedCount > 0 -> {
                        val msg = when {
                            skippedDuplicates > 0 -> "Queued $enqueuedCount items ($skippedDuplicates already saved)"
                            enqueuedCount > 1 -> "Queued $enqueuedCount downloads"
                            else -> "Download queued"
                        }
                        _uiState.update { it.copy(isEnqueuing = false, downloadEnqueuedMessage = msg, errorMessage = null) }
                    }
                    skippedDuplicates > 0 -> {
                        val msg = if (streams.size > 1) "All slides are already queued or saved." else "This item is already queued or saved."
                        _uiState.update { it.copy(isEnqueuing = false, errorMessage = msg) }
                    }
                    else -> {
                        _uiState.update { it.copy(isEnqueuing = false, errorMessage = firstError?.localizedMessage ?: "Failed to queue download.") }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isEnqueuing = false, errorMessage = e.localizedMessage ?: "Failed to queue download.") }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearEnqueuedMessage() {
        _uiState.update { it.copy(downloadEnqueuedMessage = null) }
    }

    fun clearPreview() {
        _uiState.update {
            it.copy(
                mediaInfo = null,
                availableStreams = emptyList(),
                selectedStream = null,
                selectedPlaylistVideoIds = emptySet(),
                playlistEnqueueProgress = null
            )
        }
    }

    fun onTogglePlaylistVideo(videoId: String) {
        _uiState.update { state ->
            val current = state.selectedPlaylistVideoIds.toMutableSet()
            if (!current.add(videoId)) {
                current.remove(videoId)
            }
            state.copy(selectedPlaylistVideoIds = current)
        }
    }

    fun onSelectAllPlaylistVideos(selectAll: Boolean) {
        _uiState.update { state ->
            val allIds = if (selectAll) {
                state.mediaInfo?.playlistItems.orEmpty().mapTo(linkedSetOf()) { it.videoId }
            } else {
                emptySet()
            }
            state.copy(selectedPlaylistVideoIds = allIds)
        }
    }

    fun onSelectPlaylistQuality(quality: String) {
        _uiState.update { it.copy(playlistQuality = quality) }
    }

    fun onToggleOrganizeInPlaylistFolder(enabled: Boolean) {
        _uiState.update { it.copy(organizeInPlaylistFolder = enabled) }
    }

    fun onToggleNumberPlaylistEpisodes(enabled: Boolean) {
        _uiState.update { it.copy(numberPlaylistEpisodes = enabled) }
    }

    fun startPlaylistBatchDownload() {
        val state = _uiState.value
        val media = state.mediaInfo ?: return
        if (!media.isPlaylist || state.isEnqueuing) return
        val totalPlaylistItems = media.playlistItems.size
        val selectedItems = media.playlistItems
            .mapIndexed { idx, item -> (idx + 1) to item }
            .filter { (_, item) -> item.videoId in state.selectedPlaylistVideoIds }
        if (selectedItems.isEmpty()) return

        val targetQuality = state.playlistQuality
        val playlistSubfolder = if (state.organizeInPlaylistFolder) sanitizePlaylistFolderName(media.title) else null
        val numberEpisodes = state.numberPlaylistEpisodes
        _uiState.update {
            it.copy(
                isEnqueuing = true,
                playlistEnqueueProgress = "0/${selectedItems.size}",
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                var enqueuedCount = 0
                var skippedDuplicates = 0
                var firstError: Exception? = null

                for ((index, entry) in selectedItems.withIndex()) {
                    val (episodeNumber, item) = entry
                    _uiState.update {
                        it.copy(playlistEnqueueProgress = "${index + 1}/${selectedItems.size}")
                    }
                    try {
                        val streamsResult = resolveStreamsUseCase(item.videoId, item.url)
                        val streams = streamsResult.getOrElse { err ->
                            throw (err as? Exception ?: Exception(err.localizedMessage ?: "Stream resolution failed"))
                        }
                        if (streams.isEmpty()) {
                            throw IllegalStateException("No downloadable streams for ${item.title}")
                        }

                        val chosen = if (targetQuality.equals("Audio", ignoreCase = true)) {
                            selectPreferredAudioStream(streams)
                        } else {
                            selectPreferredStream(streams, targetQuality)
                        } ?: throw IllegalStateException("No matching stream for ${item.title}")

                        val audio = if (chosen.isVideoOnly) chosen.companionAudio(streams) else null
                        val primary = if (chosen.isVideoOnly && audio == null) {
                            streams.firstOrNull { it.isProgressive }
                                ?: throw IllegalStateException("No compatible audio stream for ${item.title}")
                        } else {
                            chosen
                        }

                        val resolvedTitle = if (numberEpisodes) {
                            formatPlaylistEpisodeTitle(episodeNumber, totalPlaylistItems, item.title)
                        } else {
                            item.title
                        }
                        val task = DownloadTask(
                            id = UUID.randomUUID().toString(),
                            url = item.url,
                            platform = media.platform,
                            mediaId = item.videoId,
                            title = resolvedTitle,
                            thumbnailUrl = primary.thumbnailUrl ?: item.thumbnailUrl ?: media.thumbnailUrl,
                            status = DownloadStatus.QUEUED,
                            selectedQuality = primary.quality,
                            selectedFormat = primary.format,
                            mimeType = primary.mimeType,
                            totalBytes = primary.fileSize,
                            durationSeconds = item.durationSeconds,
                            subfolder = playlistSubfolder
                        )
                        enqueueDownloadUseCase(task, primary, audio)
                        enqueuedCount++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: AppError.DuplicateDownload) {
                        skippedDuplicates++
                    } catch (e: Exception) {
                        if (selectedItems.size == 1) throw e
                        if (firstError == null) firstError = e
                    }
                }

                when {
                    enqueuedCount > 0 -> {
                        val msg = when {
                            skippedDuplicates > 0 -> "Queued $enqueuedCount videos ($skippedDuplicates already saved)"
                            enqueuedCount > 1 -> "Queued $enqueuedCount playlist downloads"
                            else -> "Download queued"
                        }
                        _uiState.update {
                            it.copy(
                                isEnqueuing = false,
                                playlistEnqueueProgress = null,
                                downloadEnqueuedMessage = msg,
                                errorMessage = null
                            )
                        }
                    }
                    skippedDuplicates > 0 -> {
                        val msg = if (selectedItems.size > 1) {
                            "All selected videos are already queued or saved."
                        } else {
                            "This item is already queued or saved."
                        }
                        _uiState.update {
                            it.copy(isEnqueuing = false, playlistEnqueueProgress = null, errorMessage = msg)
                        }
                    }
                    else -> {
                        _uiState.update {
                            it.copy(
                                isEnqueuing = false,
                                playlistEnqueueProgress = null,
                                errorMessage = firstError?.localizedMessage ?: "Failed to queue playlist downloads."
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isEnqueuing = false,
                        playlistEnqueueProgress = null,
                        errorMessage = e.localizedMessage ?: "Failed to queue playlist downloads."
                    )
                }
            }
        }
    }

    internal fun selectPreferredStream(
        streams: List<StreamInfo>,
        preferredQuality: String
    ): StreamInfo? {
        if (streams.isEmpty()) return null
        val mediaStreams = streams.filter { !it.isAudioOnly }
        if (mediaStreams.isEmpty()) return streams.firstOrNull()

        val comparator = compareBy<StreamInfo> { !it.isVideoOnly }
            .thenBy { it.format.equals("mp4", ignoreCase = true) }

        val exactMatches = mediaStreams.filter { it.quality.equals(preferredQuality, ignoreCase = true) }
        if (exactMatches.isNotEmpty()) {
            return exactMatches.maxWithOrNull(comparator)
        }

        val prefixMatches = mediaStreams.filter {
            it.quality?.startsWith(preferredQuality, ignoreCase = true) == true
        }
        if (prefixMatches.isNotEmpty()) {
            return prefixMatches.maxWithOrNull(comparator)
        }

        val targetHeight = preferredQuality.takeWhile { it.isDigit() }.toIntOrNull()
        if (targetHeight != null) {
            val withHeights = mediaStreams.mapNotNull { stream ->
                val height = stream.quality?.takeWhile { it.isDigit() }?.toIntOrNull()
                if (height != null) stream to height else null
            }
            val withinLimit = withHeights
                .filter { it.second <= targetHeight }
                .maxWithOrNull(
                    compareBy<Pair<StreamInfo, Int>> { it.second }
                        .thenBy(comparator) { it.first }
                )?.first
            if (withinLimit != null) return withinLimit
        }

        return mediaStreams.firstOrNull()
    }

    internal fun selectPreferredAudioStream(streams: List<StreamInfo>): StreamInfo? {
        val audioStreams = streams.filter { it.isAudioOnly }
        if (audioStreams.isEmpty()) return streams.firstOrNull()
        val compatible = audioStreams.filter { stream ->
            val fmt = stream.format?.lowercase()
            val mime = stream.mimeType?.lowercase()
            fmt in setOf("m4a", "mp3", "aac", "ogg") ||
                mime in setOf("audio/mp4", "audio/m4a", "audio/mpeg", "audio/aac", "audio/ogg")
        }
        return (compatible.ifEmpty { audioStreams }).maxByOrNull { it.bitrate ?: 0 }
    }

    internal fun sanitizePlaylistFolderName(rawTitle: String?): String? {
        return FileNameSanitizer.sanitize(rawTitle, fallback = "")
            .take(60)
            .trimEnd('.', '_', '-', ' ')
            .ifBlank { null }
    }

    internal fun formatPlaylistEpisodeTitle(
        episodeNumber: Int,
        totalCount: Int,
        rawTitle: String
    ): String {
        val padWidth = maxOf(2, totalCount.coerceAtLeast(1).toString().length)
        val prefix = episodeNumber.toString().padStart(padWidth, '0')
        val trimmed = rawTitle.trim().ifBlank { "Episode $prefix" }
        val alreadyNumbered = Regex("^0*$episodeNumber\\s*[-._)]\\s+").containsMatchIn(trimmed)
        return if (alreadyNumbered) trimmed else "$prefix - $trimmed"
    }
}
