package com.mediasaver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.mediasaver.core.common.AppLogger
import com.mediasaver.core.download.work.StartupCleanupWorker
import dagger.hilt.android.HiltAndroidApp

/**
 * Main Application entry point for Personal Media Saver.
 * Annotated with [@HiltAndroidApp] to initiate Hilt code generation and dependency injection graph.
 *
 * Adheres to the strict Zero-Telemetry contract: No analytics, crash reporting, or remote tracking.
 */
@HiltAndroidApp
class MediaSaverApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        
        AppLogger.isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (AppLogger.isDebug) {
            AppLogger.loggerDelegate = { priority, tag, message, throwable ->
                when (priority) {
                    3 -> android.util.Log.d(tag, message)
                    4 -> android.util.Log.i(tag, message)
                    5 -> android.util.Log.w(tag, message)
                    6 -> android.util.Log.e(tag, message, throwable)
                }
            }
        }

        createNotificationChannel()
        scheduleStartupCleanup()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Downloads"
            val descriptionText = "Active and completed downloads"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel("download_channel", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun scheduleStartupCleanup() {
        val cleanupWorkRequest = OneTimeWorkRequestBuilder<StartupCleanupWorker>().build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            "StartupCleanup",
            ExistingWorkPolicy.KEEP,
            cleanupWorkRequest
        )
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
