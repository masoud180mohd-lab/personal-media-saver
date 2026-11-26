package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * Use case to pause an active or queued download while preserving partial bytes on disk.
 */
class PauseDownloadUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    suspend operator fun invoke(downloadId: String) {
        downloadRepository.pause(downloadId)
    }
}
