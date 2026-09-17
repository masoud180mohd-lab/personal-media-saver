package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.DownloadRepository
import com.mediasaver.core.model.DownloadProgressInfo
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case to observe continuous byte streaming progress updates.
 */
class GetDownloadProgressUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    operator fun invoke(): Flow<DownloadProgressInfo> {
        return downloadRepository.observeProgress()
    }
}
