package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.SettingsRepository
import com.mediasaver.core.model.ThemeMode
import javax.inject.Inject

/**
 * Use case to mutate application settings.
 */
class UpdateSettingsUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend fun updateThemeMode(themeMode: ThemeMode) {
        settingsRepository.updateThemeMode(themeMode)
    }

    suspend fun updatePreferredQuality(quality: String) {
        settingsRepository.updatePreferredQuality(quality)
    }

    suspend fun updateMaxConcurrentDownloads(limit: Int) {
        settingsRepository.updateMaxConcurrentDownloads(limit)
    }

    suspend fun updateDownloadDirectoryUri(uri: String?) {
        settingsRepository.updateDownloadDirectoryUri(uri)
    }

    suspend fun resetToDefaults() {
        settingsRepository.resetToDefaults()
    }
}
