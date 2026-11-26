package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * Use case to cancel all active, queued, and paused downloads.
 */
class CancelAllDownloadsUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    suspend operator fun invoke() {
        downloadRepository.cancelAll()
    }
}
