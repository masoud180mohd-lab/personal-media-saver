package com.mediasaver.feature.download

import com.mediasaver.core.model.DownloadProgressInfo
import com.mediasaver.core.model.DownloadTask

/**
 * Immutable UI state representing active downloads queue.
 */
data class DownloadUiState(
    val activeTasks: List<DownloadTask> = emptyList(),
    val liveProgressMap: Map<String, DownloadProgressInfo> = emptyMap(),
    val isLoading: Boolean = false,
    val userMessage: String? = null
)
