package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.StatusRepository
import com.mediasaver.core.model.WhatsAppStatus
import javax.inject.Inject

/**
 * Use case to scan, discover, and index available WhatsApp status media files.
 */
class ScanStatusesUseCase @Inject constructor(
    private val statusRepository: StatusRepository
) {
    suspend operator fun invoke(isBusiness: Boolean = false): Result<List<WhatsAppStatus>> {
        return statusRepository.scanStatuses(isBusiness)
    }
}
