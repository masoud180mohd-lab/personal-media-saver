package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.StatusRepository
import javax.inject.Inject

/**
 * Use case to verify whether Storage Access Framework access has been granted for WhatsApp statuses.
 */
class CheckStatusPermissionUseCase @Inject constructor(
    private val statusRepository: StatusRepository
) {
    suspend operator fun invoke(isBusiness: Boolean = false): Boolean {
        return statusRepository.isAccessGranted(isBusiness)
    }
}
