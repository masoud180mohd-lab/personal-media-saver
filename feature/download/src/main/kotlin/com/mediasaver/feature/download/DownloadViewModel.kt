package com.mediasaver.feature.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediasaver.core.domain.usecase.CancelAllDownloadsUseCase
import com.mediasaver.core.domain.usecase.CancelDownloadUseCase
import com.mediasaver.core.domain.usecase.DeleteDownloadUseCase
import com.mediasaver.core.domain.usecase.GetDownloadProgressUseCase
import com.mediasaver.core.domain.usecase.GetQueueDownloadsUseCase
import com.mediasaver.core.domain.usecase.PauseAllDownloadsUseCase
import com.mediasaver.core.domain.usecase.PauseDownloadUseCase
import com.mediasaver.core.domain.usecase.ResumeAllDownloadsUseCase
import com.mediasaver.core.domain.usecase.ResumeDownloadUseCase
import com.mediasaver.core.domain.usecase.RetryAllFailedDownloadsUseCase
import com.mediasaver.core.domain.usecase.RetryDownloadUseCase
import com.mediasaver.core.model.error.AppError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val getQueueDownloadsUseCase: GetQueueDownloadsUseCase,
    private val getDownloadProgressUseCase: GetDownloadProgressUseCase,
    private val cancelDownloadUseCase: CancelDownloadUseCase,
    private val retryDownloadUseCase: RetryDownloadUseCase,
    private val deleteDownloadUseCase: DeleteDownloadUseCase,
    private val pauseDownloadUseCase: PauseDownloadUseCase,
    private val resumeDownloadUseCase: ResumeDownloadUseCase,
    private val pauseAllDownloadsUseCase: PauseAllDownloadsUseCase,
    private val resumeAllDownloadsUseCase: ResumeAllDownloadsUseCase,
    private val cancelAllDownloadsUseCase: CancelAllDownloadsUseCase,
    private val retryAllFailedDownloadsUseCase: RetryAllFailedDownloadsUseCase
) : ViewModel() {

    private val _liveProgress = MutableStateFlow<Map<String, com.mediasaver.core.model.DownloadProgressInfo>>(emptyMap())
    private val _userMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<DownloadUiState> = combine(
        getQueueDownloadsUseCase(),
        _liveProgress,
        _userMessage
    ) { queueTasks, progressMap, message ->
        DownloadUiState(
            activeTasks = queueTasks,
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

    private fun runQueueAction(successMessage: String?, errorAction: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                if (successMessage != null) {
                    _userMessage.value = successMessage
                }
            } catch (e: AppError) {
                _userMessage.value = e.userMessage
            } catch (e: Exception) {
                _userMessage.value = "Failed to $errorAction: ${e.localizedMessage ?: "Unknown error"}"
            }
        }
    }

    fun pauseDownload(downloadId: String) =
        runQueueAction("Download paused", "pause download") { pauseDownloadUseCase(downloadId) }

    fun resumeDownload(downloadId: String) =
        runQueueAction("Download resumed", "resume download") { resumeDownloadUseCase(downloadId) }

    fun pauseAll() =
        runQueueAction("Paused active downloads", "pause downloads") { pauseAllDownloadsUseCase() }

    fun resumeAll() =
        runQueueAction("Resumed paused downloads", "resume downloads") { resumeAllDownloadsUseCase() }

    fun cancelAll() =
        runQueueAction("Cancelled queue downloads", "cancel downloads") { cancelAllDownloadsUseCase() }

    fun retryAllFailed() =
        runQueueAction("Retrying failed downloads", "retry failed downloads") { retryAllFailedDownloadsUseCase() }

    fun cancelDownload(downloadId: String) =
        runQueueAction("Download cancelled", "cancel download") { cancelDownloadUseCase(downloadId) }

    fun retryDownload(downloadId: String) =
        runQueueAction("Download restarted", "retry download") { retryDownloadUseCase(downloadId) }

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
