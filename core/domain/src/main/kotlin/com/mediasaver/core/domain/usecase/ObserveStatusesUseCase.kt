package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.StatusRepository
import com.mediasaver.core.model.WhatsAppStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case to observe scanned WhatsApp statuses cached in local database.
 */
class ObserveStatusesUseCase @Inject constructor(
    private val statusRepository: StatusRepository
) {
    operator fun invoke(): Flow<List<WhatsAppStatus>> {
        return statusRepository.observeStatuses()
    }
}
