package com.mediasaver.feature.settings

import com.mediasaver.core.model.AppSettings

/**
 * Immutable UI state for application settings.
 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val appVersion: String = "1.0.0",
    val userMessage: String? = null
)
