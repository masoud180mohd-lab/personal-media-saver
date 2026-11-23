package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.DownloadRepository
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Use case to observe active, queued, failed, and cancelled download tasks for the Downloads queue UI.
 */
class GetQueueDownloadsUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    operator fun invoke(): Flow<List<DownloadTask>> {
        return downloadRepository.getAllDownloads().map { tasks ->
            tasks.filter { it.status != DownloadStatus.COMPLETED }
                .sortedBy { if (it.status.isActive) 0 else 1 }
        }
    }
}
