package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * Use case to pause all pausable active and queued downloads.
 */
class PauseAllDownloadsUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    suspend operator fun invoke() {
        downloadRepository.pauseAll()
    }
}
