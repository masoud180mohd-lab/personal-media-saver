package com.mediasaver.feature.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediasaver.core.domain.usecase.CheckStatusPermissionUseCase
import com.mediasaver.core.domain.usecase.ObserveStatusesUseCase
import com.mediasaver.core.domain.usecase.SaveStatusUriUseCase
import com.mediasaver.core.domain.usecase.SaveStatusUseCase
import com.mediasaver.core.domain.usecase.ScanStatusesUseCase
import com.mediasaver.core.model.WhatsAppStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StatusViewModel @Inject constructor(
    private val checkStatusPermissionUseCase: CheckStatusPermissionUseCase,
    private val scanStatusesUseCase: ScanStatusesUseCase,
    private val saveStatusUseCase: SaveStatusUseCase,
    private val observeStatusesUseCase: ObserveStatusesUseCase,
    private val saveStatusUriUseCase: SaveStatusUriUseCase
) : ViewModel() {

    private val _isPermissionGranted = MutableStateFlow(false)
    private val _isScanning = MutableStateFlow(false)
    private val _filter = MutableStateFlow(StatusFilter.ALL)
    private val _isBusinessWhatsApp = MutableStateFlow(false)
    private val _savingStatusId = MutableStateFlow<String?>(null)
    private val _userMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<StatusUiState> = combine(
        observeStatusesUseCase(),
        _isPermissionGranted,
        _isScanning,
        _filter,
        combine(_isBusinessWhatsApp, _savingStatusId, _userMessage) { isBusiness, savingId, message ->
            Triple(isBusiness, savingId, message)
        }
    ) { statuses, granted, scanning, filter, extra ->
        val (isBusiness, savingId, message) = extra
        StatusUiState(
            isPermissionGranted = granted,
            isScanning = scanning,
            statuses = statuses,
            filter = filter,
            isBusinessWhatsApp = isBusiness,
            savingStatusId = savingId,
            userMessage = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StatusUiState()
    )

    init {
        checkPermissionAndScan()
    }

    fun saveStatusUri(uriString: String) {
        viewModelScope.launch {
            saveStatusUriUseCase(uriString, _isBusinessWhatsApp.value)
            checkPermissionAndScan()
        }
    }

    fun checkPermissionAndScan() {
        viewModelScope.launch {
            val granted = checkStatusPermissionUseCase(_isBusinessWhatsApp.value)
            _isPermissionGranted.value = granted
            if (granted) {
                scanStatuses()
            }
        }
    }

    fun scanStatuses() {
        viewModelScope.launch {
            _isScanning.value = true
            val result = scanStatusesUseCase(_isBusinessWhatsApp.value)
            _isScanning.value = false
            result.onFailure { error ->
                _userMessage.value = "Failed to scan statuses: ${error.localizedMessage}"
            }
        }
    }

    fun setFilter(filter: StatusFilter) {
        _filter.value = filter
    }

    fun toggleBusinessWhatsApp(isBusiness: Boolean) {
        _isBusinessWhatsApp.value = isBusiness
        checkPermissionAndScan()
    }

    fun saveStatus(status: WhatsAppStatus) {
        viewModelScope.launch {
            _savingStatusId.value = status.id
            val result = saveStatusUseCase(status)
            _savingStatusId.value = null
            result.onSuccess {
                _userMessage.value = "Status saved to gallery!"
            }.onFailure { error ->
                _userMessage.value = "Failed to save status: ${error.localizedMessage}"
            }
        }
    }

    fun dismissMessage() {
        _userMessage.value = null
    }
}
