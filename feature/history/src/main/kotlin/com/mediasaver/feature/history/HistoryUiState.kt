package com.mediasaver.feature.history

import com.mediasaver.core.model.DownloadTask

/**
 * Immutable UI state for download history screen.
 */
data class HistoryUiState(
    val completedItems: List<DownloadTask> = emptyList(),
    val isLoading: Boolean = false,
    val showClearAllDialog: Boolean = false,
    val itemPendingDelete: DownloadTask? = null,
    val userMessage: String? = null
)
