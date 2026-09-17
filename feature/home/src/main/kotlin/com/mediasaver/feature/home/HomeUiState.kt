package com.mediasaver.feature.home

import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo

/**
 * Immutable UI state for the Home screen.
 */
data class HomeUiState(
    val urlInput: String = "",
    val detectedPlatform: PlatformId = PlatformId.UNKNOWN,
    val isExtracting: Boolean = false,
    val mediaInfo: MediaInfo? = null,
    val availableStreams: List<StreamInfo> = emptyList(),
    val selectedStream: StreamInfo? = null,
    val isEnqueuing: Boolean = false,
    val downloadEnqueuedMessage: String? = null,
    val errorMessage: String? = null
)
