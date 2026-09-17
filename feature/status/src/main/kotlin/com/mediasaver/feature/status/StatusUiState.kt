package com.mediasaver.feature.status

import com.mediasaver.core.model.StatusMediaType
import com.mediasaver.core.model.WhatsAppStatus

enum class StatusFilter {
    ALL,
    IMAGES,
    VIDEOS
}

/**
 * Immutable UI state for WhatsApp status browser screen.
 */
data class StatusUiState(
    val isPermissionGranted: Boolean = false,
    val isScanning: Boolean = false,
    val statuses: List<WhatsAppStatus> = emptyList(),
    val filter: StatusFilter = StatusFilter.ALL,
    val isBusinessWhatsApp: Boolean = false,
    val savingStatusId: String? = null,
    val userMessage: String? = null
) {
    val filteredStatuses: List<WhatsAppStatus>
        get() = when (filter) {
            StatusFilter.ALL -> statuses
            StatusFilter.IMAGES -> statuses.filter { it.mediaType == StatusMediaType.IMAGE }
            StatusFilter.VIDEOS -> statuses.filter { it.mediaType == StatusMediaType.VIDEO }
        }
}
