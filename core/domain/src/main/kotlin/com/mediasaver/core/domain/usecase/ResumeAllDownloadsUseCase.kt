package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.DownloadRepository
import javax.inject.Inject

/**
 * Use case to resume all paused downloads back into the queue.
 */
class ResumeAllDownloadsUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    suspend operator fun invoke() {
        downloadRepository.resumeAll()
    }
}
