package com.mediasaver.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mediasaver.core.domain.repository.SettingsRepository
import com.mediasaver.core.model.AppSettings
import com.mediasaver.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore Preferences implementation of [SettingsRepository].
 * Manages reactive preference flows with zero network transmission.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    private object PreferencesKeys {
        val PREFERRED_QUALITY = stringPreferencesKey("preferred_quality")
        val DOWNLOAD_DIRECTORY_URI = stringPreferencesKey("download_directory_uri")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
        val WIFI_ONLY_DOWNLOAD = booleanPreferencesKey("wifi_only_download")
        val MAX_DOWNLOAD_SIZE_MB = intPreferencesKey("max_download_size_mb")
        val WHATSAPP_STATUS_URI = stringPreferencesKey("whatsapp_status_uri")
        val WHATSAPP_BUSINESS_STATUS_URI = stringPreferencesKey("whatsapp_business_status_uri")
        val SAVE_DOWNLOAD_HISTORY = booleanPreferencesKey("save_download_history")
        val AUTO_DELETE_TEMP_FILES = booleanPreferencesKey("auto_delete_temp_files")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppSettings(
                preferredQuality = preferences[PreferencesKeys.PREFERRED_QUALITY] ?: "720p",
                downloadDirectoryUri = preferences[PreferencesKeys.DOWNLOAD_DIRECTORY_URI],
                maxConcurrentDownloads = preferences[PreferencesKeys.MAX_CONCURRENT_DOWNLOADS] ?: 3,
                wifiOnlyDownload = preferences[PreferencesKeys.WIFI_ONLY_DOWNLOAD] ?: false,
                maxDownloadSizeMb = preferences[PreferencesKeys.MAX_DOWNLOAD_SIZE_MB] ?: 2048,
                whatsappStatusUri = preferences[PreferencesKeys.WHATSAPP_STATUS_URI],
                whatsappBusinessStatusUri = preferences[PreferencesKeys.WHATSAPP_BUSINESS_STATUS_URI],
                saveDownloadHistory = preferences[PreferencesKeys.SAVE_DOWNLOAD_HISTORY] ?: true,
                autoDeleteTempFiles = preferences[PreferencesKeys.AUTO_DELETE_TEMP_FILES] ?: true,
                themeMode = preferences[PreferencesKeys.THEME_MODE]?.let {
                    runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM)
                } ?: ThemeMode.SYSTEM
            )
        }

    override suspend fun getSettings(): AppSettings = settings.first()

    override suspend fun updatePreferredQuality(quality: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PREFERRED_QUALITY] = quality
        }
    }

    override suspend fun updateDownloadDirectoryUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri != null) {
                preferences[PreferencesKeys.DOWNLOAD_DIRECTORY_URI] = uri
            } else {
                preferences.remove(PreferencesKeys.DOWNLOAD_DIRECTORY_URI)
            }
        }
    }

    override suspend fun updateMaxConcurrentDownloads(limit: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_CONCURRENT_DOWNLOADS] = limit.coerceIn(1, 10)
        }
    }

    override suspend fun updateWifiOnlyDownload(wifiOnly: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.WIFI_ONLY_DOWNLOAD] = wifiOnly
        }
    }

    override suspend fun updateMaxDownloadSizeMb(maxSizeMb: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_DOWNLOAD_SIZE_MB] = maxSizeMb
        }
    }

    override suspend fun updateWhatsAppStatusUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri != null) {
                preferences[PreferencesKeys.WHATSAPP_STATUS_URI] = uri
            } else {
                preferences.remove(PreferencesKeys.WHATSAPP_STATUS_URI)
            }
        }
    }

    override suspend fun updateWhatsAppBusinessStatusUri(uri: String?) {
        dataStore.edit { preferences ->
            if (uri != null) {
                preferences[PreferencesKeys.WHATSAPP_BUSINESS_STATUS_URI] = uri
            } else {
                preferences.remove(PreferencesKeys.WHATSAPP_BUSINESS_STATUS_URI)
            }
        }
    }

    override suspend fun updateSaveDownloadHistory(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SAVE_DOWNLOAD_HISTORY] = enabled
        }
    }

    override suspend fun updateAutoDeleteTempFiles(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_DELETE_TEMP_FILES] = enabled
        }
    }

    override suspend fun updateThemeMode(themeMode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = themeMode.name
        }
    }

    override suspend fun resetToDefaults() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
    }
}
