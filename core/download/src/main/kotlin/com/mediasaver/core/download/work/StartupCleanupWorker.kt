package com.mediasaver.core.download.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.storage.TempFileManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class StartupCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StartupCleanupWorkerEntryPoint {
        fun tempFileManager(): TempFileManager
        fun downloadDao(): DownloadDao
    }

    override suspend fun doWork(): Result {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(
                applicationContext,
                StartupCleanupWorkerEntryPoint::class.java
            )
            val tempFileManager = entryPoint.tempFileManager()
            val downloadDao = entryPoint.downloadDao()

            // 1. Storage cleanup: delete any orphaned temporary files leftover from previous sessions
            tempFileManager.cleanOrphanedTempFiles(maxAgeMillis = 0L)

            // 2. Database cleanup: recover or fail tasks interrupted mid-stream
            val activeDownloads = downloadDao.findActiveDownloads()
            for (download in activeDownloads) {
                if (download.status == "DOWNLOADING" || download.status == "EXTRACTING" || download.status == "MUXING") {
                    if (download.retryCount < 3) {
                        // Reset to QUEUED for automatic retry
                        downloadDao.updateStatus(download.id, "QUEUED")
                    } else {
                        downloadDao.markFailed(
                            id = download.id,
                            errorType = "InterruptedException",
                            errorMessage = "Download interrupted by application shutdown"
                        )
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
