package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.DownloadRepository
import com.mediasaver.core.model.DownloadTask
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case to observe the flow of currently active and queued downloads.
 */
class GetActiveDownloadsUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    operator fun invoke(): Flow<List<DownloadTask>> {
        return downloadRepository.getActiveDownloads()
    }
}
