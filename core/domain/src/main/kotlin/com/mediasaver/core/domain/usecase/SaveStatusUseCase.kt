package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.StatusRepository
import com.mediasaver.core.model.WhatsAppStatus
import javax.inject.Inject

/**
 * Use case to copy a selected WhatsApp status media file into the device's public MediaStore gallery.
 */
class SaveStatusUseCase @Inject constructor(
    private val statusRepository: StatusRepository
) {
    suspend operator fun invoke(status: WhatsAppStatus, overwrite: Boolean = false): Result<String> {
        return statusRepository.saveStatus(status, overwrite)
    }
}
