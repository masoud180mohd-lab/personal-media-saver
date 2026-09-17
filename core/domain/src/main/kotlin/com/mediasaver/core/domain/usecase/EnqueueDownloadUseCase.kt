package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.repository.DownloadRepository
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.StreamInfo
import javax.inject.Inject

/**
 * Use case to enqueue a new download task with chosen video and optional audio streams.
 */
class EnqueueDownloadUseCase @Inject constructor(
    private val downloadRepository: DownloadRepository
) {
    suspend operator fun invoke(
        task: DownloadTask,
        videoStream: StreamInfo,
        audioStream: StreamInfo? = null
    ) {
        downloadRepository.enqueue(task, videoStream, audioStream)
    }
}
