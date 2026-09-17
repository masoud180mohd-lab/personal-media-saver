package com.mediasaver.feature.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediasaver.core.domain.usecase.CancelDownloadUseCase
import com.mediasaver.core.domain.usecase.DeleteDownloadUseCase
import com.mediasaver.core.domain.usecase.GetActiveDownloadsUseCase
import com.mediasaver.core.domain.usecase.GetDownloadProgressUseCase
import com.mediasaver.core.domain.usecase.RetryDownloadUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val getActiveDownloadsUseCase: GetActiveDownloadsUseCase,
    private val getDownloadProgressUseCase: GetDownloadProgressUseCase,
    private val cancelDownloadUseCase: CancelDownloadUseCase,
    private val retryDownloadUseCase: RetryDownloadUseCase,
    private val deleteDownloadUseCase: DeleteDownloadUseCase
) : ViewModel() {

    private val _liveProgress = MutableStateFlow<Map<String, com.mediasaver.core.model.DownloadProgressInfo>>(emptyMap())
    private val _userMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DownloadUiState> = combine(
        getActiveDownloadsUseCase(),
        _liveProgress,
        _userMessage
    ) { activeTasks, progressMap, message ->
        DownloadUiState(
            activeTasks = activeTasks,
            liveProgressMap = progressMap,
            isLoading = false,
            userMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DownloadUiState(isLoading = true)
    )

    init {
        // Collect streaming live progress emissions
        viewModelScope.launch {
            getDownloadProgressUseCase().collect { progressInfo ->
                _liveProgress.update { currentMap ->
                    currentMap + (progressInfo.downloadId to progressInfo)
                }
            }
        }
    }

    fun cancelDownload(downloadId: String) {
        viewModelScope.launch {
            try {
                cancelDownloadUseCase(downloadId)
                _userMessage.value = "Download cancelled"
            } catch (e: Exception) {
                _userMessage.value = "Failed to cancel download: ${e.localizedMessage}"
            }
        }
    }

    fun retryDownload(downloadId: String) {
        viewModelScope.launch {
            try {
                retryDownloadUseCase(downloadId)
                _userMessage.value = "Download restarted"
            } catch (e: Exception) {
                _userMessage.value = "Failed to retry download: ${e.localizedMessage}"
            }
        }
    }

    fun removeDownload(downloadId: String) {
        viewModelScope.launch {
            try {
                deleteDownloadUseCase(downloadId)
            } catch (_: Exception) {}
        }
    }

    fun dismissMessage() {
        _userMessage.value = null
    }
}
