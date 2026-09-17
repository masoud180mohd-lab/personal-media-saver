package com.mediasaver.core.download.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mediasaver.core.download.worker.DownloadProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages notification channels, active progress updates, and completion alerts for downloads.
 */
@Singleton
class DownloadNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val DOWNLOAD_NOTIFICATION_ID = 1001
        const val CHANNEL_ACTIVE_DOWNLOADS = "media_downloads_channel"
        const val CHANNEL_COMPLETED_DOWNLOADS = "media_completed_channel"

        const val ACTION_CANCEL_DOWNLOAD = "com.mediasaver.action.CANCEL_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
    }

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activeChannel = NotificationChannel(
                CHANNEL_ACTIVE_DOWNLOADS,
                "Active Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress for media files"
                setShowBadge(false)
            }

            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED_DOWNLOADS,
                "Download Completed",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when downloads have successfully finished"
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(activeChannel)
            notificationManager.createNotificationChannel(completedChannel)
        }
    }

    /**
     * Builds the foreground service notification representing active download progress.
     */
    fun buildProgressNotification(
        downloadId: String,
        title: String?,
        progress: DownloadProgress?
    ): Notification {
        val cancelIntent = Intent(ACTION_CANCEL_DOWNLOAD).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            downloadId.hashCode(),
            cancelIntent,
            flags
        )

        val percent = progress?.progressPercent ?: 0
        val isIndeterminate = progress?.totalBytes == null

        val speedText = if (progress != null && progress.speedBytesPerSec > 0) {
            val speedMb = progress.speedBytesPerSec / (1024.0 * 1024.0)
            String.format(" • %.1f MB/s", speedMb)
        } else {
            ""
        }

        val contentText = if (!isIndeterminate) {
            "$percent%$speedText"
        } else {
            "Downloading...$speedText"
        }

        return NotificationCompat.Builder(context, CHANNEL_ACTIVE_DOWNLOADS)
            .setContentTitle(title ?: "Downloading Media")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .build()
    }

    /**
     * Posts a completion notification.
     */
    fun notifyCompleted(downloadId: String, title: String?) {
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED_DOWNLOADS)
            .setContentTitle("Download Complete")
            .setContentText(title ?: "Media file saved to device")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(downloadId.hashCode(), notification)
    }

    /**
     * Posts a failure notification.
     */
    fun notifyFailed(downloadId: String, title: String?, errorMessage: String?) {
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPLETED_DOWNLOADS)
            .setContentTitle("Download Failed")
            .setContentText(errorMessage ?: "An error occurred while downloading ${title.orEmpty()}")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(downloadId.hashCode(), notification)
    }

    /**
     * Cancels the active progress notification.
     */
    fun cancelActiveNotification() {
        notificationManager.cancel(DOWNLOAD_NOTIFICATION_ID)
    }
}
