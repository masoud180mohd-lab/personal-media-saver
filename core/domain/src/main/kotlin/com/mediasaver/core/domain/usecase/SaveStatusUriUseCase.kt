package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.SettingsRepository
import javax.inject.Inject

/**
 * Use case to persist the granted Storage Access Framework (SAF) URI for WhatsApp status media.
 */
class SaveStatusUriUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    suspend operator fun invoke(uriString: String, isBusiness: Boolean = false) {
        if (isBusiness) {
            settingsRepository.updateWhatsAppBusinessStatusUri(uriString)
        } else {
            settingsRepository.updateWhatsAppStatusUri(uriString)
        }
    }
}
