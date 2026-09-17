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
import com.mediasaver.core.model.StreamInfo
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

    fun onUrlChange(newUrl: String) {
        val detected = PlatformDetector.detect(newUrl)
        _uiState.update {
            it.copy(
                urlInput = newUrl,
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

        viewModelScope.launch {
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
                val streams = streamsResult.getOrDefault(emptyList())

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

    fun startDownload() {
        val currentState = _uiState.value
        val media = currentState.mediaInfo ?: return
        val stream = currentState.selectedStream ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isEnqueuing = true, errorMessage = null) }

            try {
                val taskId = UUID.randomUUID().toString()
                val task = DownloadTask(
                    id = taskId,
                    url = currentState.urlInput.trim(),
                    platform = media.platform,
                    mediaId = media.id,
                    title = media.title,
                    thumbnailUrl = media.thumbnailUrl,
                    status = DownloadStatus.QUEUED,
                    selectedQuality = stream.quality,
                    selectedFormat = stream.format,
                    totalBytes = stream.fileSize,
                    durationSeconds = media.durationSeconds
                )

                // Check for companion audio stream if selected stream is video-only adaptive
                val audioStream = if (stream.isVideoOnly) {
                    currentState.availableStreams.firstOrNull { it.isAudioOnly }
                } else {
                    null
                }

                enqueueDownloadUseCase(task, stream, audioStream)

                _uiState.update {
                    it.copy(
                        isEnqueuing = false,
                        downloadEnqueuedMessage = "Download queued: ${media.title.take(30)}..."
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isEnqueuing = false,
                        errorMessage = e.localizedMessage ?: "Failed to queue download."
                    )
                }
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
