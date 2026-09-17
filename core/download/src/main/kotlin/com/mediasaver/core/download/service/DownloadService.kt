package com.mediasaver.core.download.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.mediasaver.core.download.notification.DownloadNotificationManager
import com.mediasaver.core.download.queue.DownloadQueueManager
import com.mediasaver.core.download.worker.DownloadWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service hosting active downloads, ensuring persistent execution
 * and updating real-time notification progress under the dataSync service type.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var queueManager: DownloadQueueManager

    @Inject
    lateinit var notificationManager: DownloadNotificationManager

    @Inject
    lateinit var downloadWorker: DownloadWorker

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressCollectorJob: Job? = null
    private var queueWatcherJob: Job? = null

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadNotificationManager.ACTION_CANCEL_DOWNLOAD) {
                val downloadId = intent.getStringExtra(DownloadNotificationManager.EXTRA_DOWNLOAD_ID)
                if (!downloadId.isNullOrBlank()) {
                    serviceScope.launch {
                        queueManager.cancel(downloadId)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        val filter = IntentFilter(DownloadNotificationManager.ACTION_CANCEL_DOWNLOAD)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cancelReceiver, filter)
        }

        startForegroundWithNotification()
        observeProgress()
        observeActiveQueue()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Redeliver intent to survive process termination
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(cancelReceiver)
        serviceScope.cancel()
        notificationManager.cancelActiveNotification()
    }

    private fun startForegroundWithNotification() {
        val initialNotification = notificationManager.buildProgressNotification(
            downloadId = "initial",
            title = "Preparing Downloads",
            progress = null
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DownloadNotificationManager.DOWNLOAD_NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                DownloadNotificationManager.DOWNLOAD_NOTIFICATION_ID,
                initialNotification
            )
        }
    }

    private fun observeProgress() {
        progressCollectorJob = serviceScope.launch {
            downloadWorker.progressFlow.collectLatest { progress ->
                val notification = notificationManager.buildProgressNotification(
                    downloadId = progress.downloadId,
                    title = "Downloading Media",
                    progress = progress
                )
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(DownloadNotificationManager.DOWNLOAD_NOTIFICATION_ID, notification)
            }
        }
    }

    private fun observeActiveQueue() {
        queueWatcherJob = serviceScope.launch {
            queueManager.activeTasksFlow.collectLatest { activeTasks ->
                if (activeTasks.isEmpty()) {
                    // No active or queued downloads remaining; terminate service cleanly
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.stopService(intent)
        }
    }
}
