package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * Use case to retry all failed downloads in the queue.
 */
class RetryAllFailedDownloadsUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    suspend operator fun invoke() {
        downloadRepository.retryAllFailed()
    }
}
