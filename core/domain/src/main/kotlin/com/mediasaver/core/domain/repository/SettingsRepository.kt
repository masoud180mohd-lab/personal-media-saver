package com.mediasaver.core.domain.repository

import com.mediasaver.core.model.AppSettings
import com.mediasaver.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface governing user application settings and preferences.
 */
interface SettingsRepository {

    /**
     * Observable stream of current application settings.
     */
    val settings: Flow<AppSettings>

    /**
     * One-shot retrieval of snapshot settings.
     */
    suspend fun getSettings(): AppSettings

    suspend fun updatePreferredQuality(quality: String)

    suspend fun updateDownloadDirectoryUri(uri: String?)

    suspend fun updateMaxConcurrentDownloads(limit: Int)

    suspend fun updateWifiOnlyDownload(wifiOnly: Boolean)

    suspend fun updateMaxDownloadSizeMb(maxSizeMb: Int)

    suspend fun updateWhatsAppStatusUri(uri: String?)

    suspend fun updateWhatsAppBusinessStatusUri(uri: String?)

    suspend fun updateSaveDownloadHistory(enabled: Boolean)

    suspend fun updateAutoDeleteTempFiles(enabled: Boolean)

    suspend fun updateThemeMode(themeMode: ThemeMode)

    suspend fun resetToDefaults()
}
