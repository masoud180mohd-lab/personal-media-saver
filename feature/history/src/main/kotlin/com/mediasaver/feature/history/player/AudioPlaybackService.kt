package com.mediasaver.feature.history.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.mediasaver.core.model.PlaybackState
import com.mediasaver.core.ui.R as UiR
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground media playback service keeping in-app audio alive in background and lock screen.
 */
@AndroidEntryPoint
class AudioPlaybackService : Service() {

    @Inject
    lateinit var playbackController: InAppPlaybackController

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceScope.launch {
            combine(playbackController.state, playbackController.coverArt) { state, art ->
                state to art
            }.collectLatest { (state, art) ->
                if (state.hasActiveItem && !state.isVideo) {
                    updateNotification(state, art)
                } else if (foregroundStarted) {
                    stopForegroundAndSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playbackController.togglePlayPause()
            ACTION_REWIND_10 -> playbackController.seekBy(-10_000L)
            ACTION_FORWARD_10 -> playbackController.seekBy(10_000L)
            ACTION_STOP -> {
                playbackController.stop()
                ensureForegroundThenStop()
                return START_NOT_STICKY
            }
        }

        val state = playbackController.state.value
        val notification = buildNotification(state, playbackController.coverArt.value)
        promoteToForeground(notification)

        if (!state.hasActiveItem || state.isVideo) {
            stopForegroundAndSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureForegroundThenStop() {
        if (!foregroundStarted) {
            promoteToForeground(buildNotification(playbackController.state.value, playbackController.coverArt.value))
        }
        stopForegroundAndSelf()
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun stopForegroundAndSelf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun updateNotification(state: PlaybackState, coverArt: Bitmap?) {
        val notification = buildNotification(state, coverArt)
        if (!foregroundStarted) {
            promoteToForeground(notification)
        } else {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(UiR.string.player_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(UiR.string.player_notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(
        state: PlaybackState,
        coverArt: Bitmap? = playbackController.coverArt.value
    ): Notification {
        val title = state.currentItem?.title?.takeIf { it.isNotBlank() }
            ?: getString(UiR.string.player_video_title_fallback)
        val artist = state.currentItem?.artist?.takeIf { it.isNotBlank() }
            ?: state.currentItem?.album?.takeIf { it.isNotBlank() }
            ?: getString(UiR.string.nav_app_name)
        val speedLabel = if (state.playbackSpeed != 1.0f) " • ${state.playbackSpeed}x" else ""

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val rewindAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_rew),
            getString(UiR.string.player_rewind_10),
            servicePendingIntent(ACTION_REWIND_10, 1)
        ).build()

        val playPauseAction = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            ),
            getString(if (state.isPlaying) UiR.string.player_pause else UiR.string.player_play),
            servicePendingIntent(ACTION_PLAY_PAUSE, 2)
        ).build()

        val forwardAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_ff),
            getString(UiR.string.player_forward_10),
            servicePendingIntent(ACTION_FORWARD_10, 3)
        ).build()

        val closeAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(UiR.string.player_close),
            servicePendingIntent(ACTION_STOP, 4)
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .apply {
                if (coverArt != null) setLargeIcon(coverArt)
            }
            .setContentTitle(title)
            .setContentText("$artist$speedLabel")
            .setContentIntent(contentIntent)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(rewindAction)
            .addAction(playPauseAction)
            .addAction(forwardAction)
            .addAction(closeAction)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(playbackController.mediaSessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val NOTIFICATION_ID = 2001
        const val CHANNEL_ID = "media_playback_channel"
        const val ACTION_START = "com.mediasaver.action.PLAYBACK_START"
        const val ACTION_PLAY_PAUSE = "com.mediasaver.action.PLAYBACK_PLAY_PAUSE"
        const val ACTION_REWIND_10 = "com.mediasaver.action.PLAYBACK_REWIND_10"
        const val ACTION_FORWARD_10 = "com.mediasaver.action.PLAYBACK_FORWARD_10"
        const val ACTION_STOP = "com.mediasaver.action.PLAYBACK_STOP"

        fun startIntent(context: Context): Intent =
            Intent(context, AudioPlaybackService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, AudioPlaybackService::class.java).setAction(ACTION_STOP)
    }
}
