package com.mediasaver.feature.settings

import com.mediasaver.core.domain.repository.SettingsRepository
import com.mediasaver.core.domain.usecase.GetSettingsUseCase
import com.mediasaver.core.domain.usecase.UpdateSettingsUseCase
import com.mediasaver.core.model.AppSettings
import com.mediasaver.core.model.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsViewModelTest {

    @Test
    fun `updateLanguageCode persists selected locale code through SettingsRepository`() = runBlocking {
        val stateFlow = MutableStateFlow(AppSettings())
        val fakeRepo = object : SettingsRepository {
            override val settings = stateFlow
            override suspend fun getSettings() = stateFlow.value
            override suspend fun updatePreferredQuality(quality: String) {}
            override suspend fun updateDownloadDirectoryUri(uri: String?) {}
            override suspend fun updateMaxConcurrentDownloads(limit: Int) {}
            override suspend fun updateWifiOnlyDownload(wifiOnly: Boolean) {}
            override suspend fun updateMaxDownloadSizeMb(maxSizeMb: Int) {}
            override suspend fun updateWhatsAppStatusUri(uri: String?) {}
            override suspend fun updateWhatsAppBusinessStatusUri(uri: String?) {}
            override suspend fun updateSaveDownloadHistory(enabled: Boolean) {}
            override suspend fun updateAutoDeleteTempFiles(enabled: Boolean) {}
            override suspend fun updateThemeMode(themeMode: ThemeMode) {}
            override suspend fun updateLanguageCode(languageCode: String) {
                stateFlow.value = stateFlow.value.copy(languageCode = languageCode.trim().lowercase())
            }
            override suspend fun resetToDefaults() {
                stateFlow.value = AppSettings()
            }
        }

        val useCase = UpdateSettingsUseCase(fakeRepo)
        useCase.updateLanguageCode("sw")
        assertEquals("sw", GetSettingsUseCase(fakeRepo).let { fakeRepo.getSettings().languageCode })

        useCase.updateLanguageCode("EN")
        assertEquals("en", fakeRepo.getSettings().languageCode)

        useCase.resetToDefaults()
        assertEquals("", fakeRepo.getSettings().languageCode)
    }
}
