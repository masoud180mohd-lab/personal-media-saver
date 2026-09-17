package com.mediasaver.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediasaver.core.domain.usecase.GetSettingsUseCase
import com.mediasaver.core.domain.usecase.UpdateSettingsUseCase
import com.mediasaver.core.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val getSettingsUseCase: GetSettingsUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase
) : ViewModel() {

    private val _userMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        getSettingsUseCase(),
        _userMessage
    ) { settings, message ->
        SettingsUiState(
            settings = settings,
            userMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun updateThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            try {
                updateSettingsUseCase.updateThemeMode(themeMode)
            } catch (e: Exception) {
                _userMessage.value = "Failed to update theme: ${e.localizedMessage}"
            }
        }
    }

    fun updatePreferredQuality(quality: String) {
        viewModelScope.launch {
            try {
                updateSettingsUseCase.updatePreferredQuality(quality)
            } catch (e: Exception) {
                _userMessage.value = "Failed to update quality: ${e.localizedMessage}"
            }
        }
    }

    fun updateMaxConcurrentDownloads(limit: Int) {
        viewModelScope.launch {
            try {
                updateSettingsUseCase.updateMaxConcurrentDownloads(limit)
            } catch (e: Exception) {
                _userMessage.value = "Failed to update concurrency limit: ${e.localizedMessage}"
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            try {
                updateSettingsUseCase.resetToDefaults()
                _userMessage.value = "Settings reset to defaults"
            } catch (e: Exception) {
                _userMessage.value = "Failed to reset settings: ${e.localizedMessage}"
            }
        }
    }

    fun dismissMessage() {
        _userMessage.value = null
    }
}
