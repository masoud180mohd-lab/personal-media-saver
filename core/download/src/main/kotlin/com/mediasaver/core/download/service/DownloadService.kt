package com.mediasaver.core.download.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MediaSaver:Download"

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

        Log.d(TAG, "DownloadService onCreate called")

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
        Log.d(TAG, "DownloadService onStartCommand called, startId=$startId")
        // Redeliver intent to survive process termination
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "DownloadService onDestroy called")
        unregisterReceiver(cancelReceiver)
        serviceScope.cancel()
        notificationManager.cancelActiveNotification()
    }

    private fun startForegroundWithNotification() {
        Log.d(TAG, "startForegroundWithNotification called")
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
        Log.d(TAG, "startForeground completed")
    }

    private fun observeProgress() {
        progressCollectorJob = serviceScope.launch {
            Log.d(TAG, "observeProgress: starting to collect progress flow")
            downloadWorker.progressFlow.collectLatest { progress ->
                Log.d(TAG, "observeProgress: received progress for ${progress.downloadId}, ${progress.progressPercent}%")
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
            Log.d(TAG, "observeActiveQueue: starting to collect active tasks flow")
            queueManager.activeTasksFlow.collectLatest { activeTasks ->
                Log.d(TAG, "observeActiveQueue: received ${activeTasks.size} active tasks")
                if (activeTasks.isEmpty()) {
                    // Add a small delay to ensure Room Flow has propagated before stopping
                    delay(500)
                    // Re-check after delay to avoid race condition
                    val currentTasks = queueManager.activeTasksFlow.firstOrNull() ?: emptyList()
                    if (currentTasks.isEmpty()) {
                        Log.d(TAG, "observeActiveQueue: no active or queued downloads remaining; terminating service")
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        Log.d(TAG, "observeActiveQueue: tasks reappeared after delay, keeping service alive")
                    }
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            Log.d(TAG, "DownloadService.start() called")
            val intent = Intent(context, DownloadService::class.java)
            ContextCompat.startForegroundService(context, intent)
            Log.d(TAG, "DownloadService.start() completed")
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.stopService(intent)
        }
    }
}
