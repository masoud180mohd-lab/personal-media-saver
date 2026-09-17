package com.mediasaver.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediasaver.core.domain.usecase.ClearHistoryUseCase
import com.mediasaver.core.domain.usecase.DeleteDownloadUseCase
import com.mediasaver.core.domain.usecase.GetDownloadHistoryUseCase
import com.mediasaver.core.model.DownloadTask
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
class HistoryViewModel @Inject constructor(
    private val getDownloadHistoryUseCase: GetDownloadHistoryUseCase,
    private val deleteDownloadUseCase: DeleteDownloadUseCase,
    private val clearHistoryUseCase: ClearHistoryUseCase
) : ViewModel() {

    private val _showClearAllDialog = MutableStateFlow(false)
    private val _itemPendingDelete = MutableStateFlow<DownloadTask?>(null)
    private val _userMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<HistoryUiState> = combine(
        getDownloadHistoryUseCase(),
        _showClearAllDialog,
        _itemPendingDelete,
        _userMessage
    ) { historyItems, showClearAll, pendingDelete, message ->
        HistoryUiState(
            completedItems = historyItems,
            isLoading = false,
            showClearAllDialog = showClearAll,
            itemPendingDelete = pendingDelete,
            userMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HistoryUiState(isLoading = true)
    )

    fun requestDeleteItem(task: DownloadTask?) {
        _itemPendingDelete.value = task
    }

    fun confirmDeleteItem() {
        val item = _itemPendingDelete.value ?: return
        viewModelScope.launch {
            try {
                deleteDownloadUseCase(item.id)
                _itemPendingDelete.value = null
                _userMessage.value = "Item removed from history"
            } catch (e: Exception) {
                _userMessage.value = "Failed to remove item: ${e.localizedMessage}"
            }
        }
    }

    fun showClearAllDialog(show: Boolean) {
        _showClearAllDialog.value = show
    }

    fun confirmClearAll() {
        viewModelScope.launch {
            try {
                clearHistoryUseCase()
                _showClearAllDialog.value = false
                _userMessage.value = "History cleared"
            } catch (e: Exception) {
                _userMessage.value = "Failed to clear history: ${e.localizedMessage}"
            }
        }
    }

    fun dismissMessage() {
        _userMessage.value = null
    }
}
