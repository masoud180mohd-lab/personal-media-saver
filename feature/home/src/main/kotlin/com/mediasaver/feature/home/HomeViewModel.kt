package com.mediasaver.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
                urlInput = newUrl, mediaInfo = null, selectedStream = null, availableStreams = emptyList(), isExtracting = false,
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
                    selectedStream = null
                )
            }

            val result = processUrlUseCase(targetUrl)
            result.onSuccess { info ->
                // Resolve stream options
                val streamsResult = resolveStreamsUseCase(info.id, targetUrl)
                val streams = streamsResult.getOrNull().orEmpty()
                if (streams.isEmpty()) {
                    _uiState.update { it.copy(isExtracting = false, mediaInfo = info, selectedStream = null,
                        availableStreams = emptyList(), errorMessage = streamsResult.exceptionOrNull()?.localizedMessage
                            ?: "No downloadable streams are available.") }
                    return@launch
                }

                // Select preferred stream from user settings or fallback to first available
                val settings = getSettingsUseCase().firstOrNull()
                val preferredQuality = settings?.preferredQuality ?: "720p"

                val chosenStream = streams.firstOrNull { it.quality.equals(preferredQuality, ignoreCase = true) }
                    ?: streams.firstOrNull { !it.isAudioOnly }
                    ?: streams.firstOrNull()

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
                for (requested in streams) {
                    val audio = if (requested.isVideoOnly) requested.companionAudio(state.availableStreams) else null
                    val primary = if (requested.isVideoOnly && audio == null)
                        state.availableStreams.firstOrNull { it.isProgressive } ?: error("No compatible audio stream is available.")
                        else requested
                    val task = DownloadTask(id = UUID.randomUUID().toString(), url = media.sourceUrl,
                        platform = media.platform, mediaId = media.id, title = media.title, thumbnailUrl = primary.thumbnailUrl ?: media.thumbnailUrl,
                        status = DownloadStatus.QUEUED, selectedQuality = primary.quality, selectedFormat = primary.format,
                        totalBytes = primary.fileSize, durationSeconds = media.durationSeconds)
                    enqueueDownloadUseCase(task, primary, audio)
                }
                _uiState.update { it.copy(isEnqueuing = false, downloadEnqueuedMessage = "Download queued") }
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
                selectedStream = null
            )
        }
    }
}
