package com.mediasaver.feature.history.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState as AndroidPlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import coil.imageLoader
import coil.request.ImageRequest
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlaybackState
import com.mediasaver.core.model.SUPPORTED_PLAYBACK_SPEEDS
import com.mediasaver.core.ui.R as UiR
import com.mediasaver.core.ui.util.extractEmbeddedAudioCoverBytesSync
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single controller managing in-app audio/video playback, speed control, position resume,
 * audio focus, and MediaSession integration across UI and background playback service.
 */
@Singleton
class InAppPlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val positionStore: PlaybackPositionStore
) {
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val _state = MutableStateFlow(PlaybackState(playbackSpeed = positionStore.getPlaybackSpeed()))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _videoAspectRatio = MutableStateFlow(16f / 9f)
    val videoAspectRatio: StateFlow<Float> = _videoAspectRatio.asStateFlow()

    private val _coverArt = MutableStateFlow<Bitmap?>(null)
    val coverArt: StateFlow<Bitmap?> = _coverArt.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var isPrepared = false
    private var surfaceHolder: SurfaceHolder? = null
    private var progressJob: Job? = null
    private var coverArtJob: Job? = null
    private var lastPersistedAtMs = 0L
    private var hasAudioFocus = false
    private var noisyReceiverRegistered = false

    private val mediaSession: MediaSession by lazy {
        MediaSession(context, "PersonalMediaSaverPlayback").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onStop() { stop() }
                override fun onSeekTo(pos: Long) { seekTo(pos) }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrevious() }
                override fun onFastForward() { seekBy(10_000L) }
                override fun onRewind() { seekBy(-10_000L) }
            }, Handler(Looper.getMainLooper()))
            isActive = true
        }
    }

    val mediaSessionToken: MediaSession.Token
        get() = mediaSession.sessionToken

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
        }
    }

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    fun play(task: DownloadTask, queue: List<DownloadTask> = listOf(task), isVideo: Boolean) {
        val path = task.filePath?.takeIf { it.isNotBlank() }
        if (path == null) {
            _state.update {
                PlaybackState(
                    currentItem = task,
                    queue = queue.ifEmpty { listOf(task) },
                    isVideo = isVideo,
                    playbackSpeed = it.playbackSpeed,
                    errorMessage = context.getString(UiR.string.player_playback_error)
                )
            }
            return
        }

        val current = _state.value
        if (current.currentItem?.id == task.id && current.isVideo == isVideo && isPrepared && mediaPlayer != null) {
            if (!current.isPlaying) resume()
            return
        }

        saveCurrentPositionNow()
        releasePlayer()

        val effectiveQueue = if (queue.any { it.id == task.id }) queue else listOf(task) + queue
        val resumePos = positionStore.getPositionMs(task.id)
        val speed = positionStore.getPlaybackSpeed()

        _state.value = PlaybackState(
            currentItem = task,
            queue = effectiveQueue,
            isVideo = isVideo,
            isPlaying = false,
            isBuffering = true,
            positionMs = resumePos,
            durationMs = (task.durationSeconds ?: 0) * 1000L,
            playbackSpeed = speed,
            errorMessage = null
        )

        if (!isVideo) {
            loadAudioCoverArt(task)
            runCatching {
                ContextCompat.startForegroundService(context, AudioPlaybackService.startIntent(context))
            }
        } else {
            coverArtJob?.cancel()
            _coverArt.value = null
            updateMediaSessionMetadata(task, null)
            runCatching { context.stopService(AudioPlaybackService.stopIntent(context)) }
        }

        try {
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(if (isVideo) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                if (path.startsWith("content://") || path.startsWith("file://")) {
                    setDataSource(context, Uri.parse(path))
                } else {
                    val file = File(path)
                    require(file.exists()) { "Media file does not exist" }
                    setDataSource(file.path)
                }

                if (isVideo) {
                    surfaceHolder?.let { runCatching { setDisplay(it) } }
                    setOnVideoSizeChangedListener { _, width, height ->
                        if (width > 0 && height > 0) {
                            _videoAspectRatio.value = width.toFloat() / height.toFloat()
                        }
                    }
                }

                setOnPreparedListener { prepared ->
                    if (mediaPlayer !== prepared) return@setOnPreparedListener
                    isPrepared = true
                    val duration = prepared.duration.coerceAtLeast(0).toLong()
                    val clampedResume = if (duration > 0L && resumePos >= duration - 2_000L) {
                        0L
                    } else {
                        resumePos.coerceAtLeast(0L)
                    }
                    if (clampedResume > 0L) {
                        runCatching { prepared.seekTo(clampedResume.toInt()) }
                    }
                    _state.update {
                        it.copy(
                            isBuffering = false,
                            durationMs = duration,
                            positionMs = clampedResume,
                            errorMessage = null
                        )
                    }
                    updateMediaSessionMetadata(_state.value.currentItem, _coverArt.value)
                    startPreparedPlayer(prepared, _state.value.playbackSpeed)
                }

                setOnCompletionListener { completed ->
                    if (mediaPlayer !== completed) return@setOnCompletionListener
                    val finished = _state.value
                    finished.currentItem?.let { positionStore.clearPosition(it.id) }
                    stopProgressTicker()

                    if (!finished.isVideo && finished.hasNext) {
                        playNext()
                    } else {
                        _state.update {
                            it.copy(
                                isPlaying = false,
                                positionMs = it.durationMs.coerceAtLeast(0L)
                            )
                        }
                        updateMediaSessionState()
                    }
                }

                setOnErrorListener { failed, _, _ ->
                    if (mediaPlayer !== failed) return@setOnErrorListener true
                    isPrepared = false
                    stopProgressTicker()
                    _state.update {
                        it.copy(
                            isBuffering = false,
                            isPlaying = false,
                            errorMessage = context.getString(UiR.string.player_playback_error)
                        )
                    }
                    updateMediaSessionState()
                    true
                }
            }
            mediaPlayer = mp
            mp.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
            _state.update {
                it.copy(
                    isBuffering = false,
                    isPlaying = false,
                    errorMessage = context.getString(UiR.string.player_playback_error)
                )
            }
        }
    }

    fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else resume()
    }

    fun pause() {
        val mp = mediaPlayer
        if (mp != null && isPrepared) {
            runCatching {
                if (mp.isPlaying) mp.pause()
            }
        }
        stopProgressTicker()
        saveCurrentPositionNow()
        _state.update { it.copy(isPlaying = false) }
        updateMediaSessionState()
    }

    fun resume() {
        val current = _state.value
        val item = current.currentItem ?: return
        val mp = mediaPlayer
        if (mp != null && isPrepared) {
            startPreparedPlayer(mp, current.playbackSpeed)
        } else {
            play(item, current.queue, current.isVideo)
        }
    }

    fun seekTo(positionMs: Long) {
        val mp = mediaPlayer ?: return
        if (!isPrepared) return
        val duration = _state.value.durationMs.coerceAtLeast(0L)
        val clamped = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        runCatching { mp.seekTo(clamped.toInt()) }
        _state.update { it.copy(positionMs = clamped) }
        _state.value.currentItem?.let { positionStore.savePositionMs(it.id, clamped, duration) }
        updateMediaSessionState()
    }

    fun seekBy(deltaMs: Long) {
        val currentPos = currentPlayerPositionMs()
        seekTo(currentPos + deltaMs)
    }

    fun setSpeed(speed: Float) {
        val normalized = SUPPORTED_PLAYBACK_SPEEDS.minByOrNull { kotlin.math.abs(it - speed) } ?: 1.0f
        positionStore.savePlaybackSpeed(normalized)
        _state.update { it.copy(playbackSpeed = normalized) }

        val mp = mediaPlayer
        if (mp != null && isPrepared && _state.value.isPlaying) {
            runCatching {
                mp.playbackParams = PlaybackParams().setSpeed(normalized)
            }
        }
        updateMediaSessionState()
    }

    fun playNext() {
        val current = _state.value
        val activeId = current.currentItem?.id ?: return
        val idx = current.queue.indexOfFirst { it.id == activeId }
        if (idx in 0 until current.queue.lastIndex) {
            play(current.queue[idx + 1], current.queue, current.isVideo)
        }
    }

    fun playPrevious() {
        val current = _state.value
        if (currentPlayerPositionMs() > 5_000L) {
            seekTo(0L)
            return
        }
        val activeId = current.currentItem?.id ?: return
        val idx = current.queue.indexOfFirst { it.id == activeId }
        if (idx > 0) {
            play(current.queue[idx - 1], current.queue, current.isVideo)
        } else {
            seekTo(0L)
        }
    }

    fun attachVideoSurface(holder: SurfaceHolder?) {
        surfaceHolder = holder
        val mp = mediaPlayer ?: return
        if (_state.value.isVideo) {
            runCatching { mp.setDisplay(holder) }
        }
    }

    fun stop() {
        saveCurrentPositionNow()
        coverArtJob?.cancel()
        _coverArt.value = null
        releasePlayer()
        abandonAudioFocus()
        unregisterNoisyReceiver()
        _state.value = PlaybackState(playbackSpeed = positionStore.getPlaybackSpeed())
        mediaSession.setMetadata(null)
        updateMediaSessionState()
        runCatching { context.stopService(AudioPlaybackService.stopIntent(context)) }
    }

    private fun startPreparedPlayer(mp: MediaPlayer, speed: Float) {
        if (!requestAudioFocus()) {
            _state.update { it.copy(isPlaying = false) }
            updateMediaSessionState()
            return
        }
        registerNoisyReceiver()

        val targetSpeed = speed.coerceIn(0.5f, 2.5f)
        runCatching {
            if (!mp.isPlaying) mp.start()
            mp.playbackParams = PlaybackParams().setSpeed(targetSpeed)
            if (!mp.isPlaying) mp.start()
        }.onFailure {
            runCatching { if (!mp.isPlaying) mp.start() }
        }

        _state.update { it.copy(isPlaying = true, isBuffering = false, errorMessage = null) }
        startProgressTicker()
        updateMediaSessionState()
    }

    private fun currentPlayerPositionMs(): Long {
        val mp = mediaPlayer
        return if (mp != null && isPrepared) {
            runCatching { mp.currentPosition.toLong().coerceAtLeast(0L) }.getOrDefault(_state.value.positionMs)
        } else {
            _state.value.positionMs
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp != null && isPrepared) {
                    val pos = runCatching { mp.currentPosition.toLong().coerceAtLeast(0L) }.getOrNull()
                    val dur = runCatching { mp.duration.toLong().coerceAtLeast(0L) }.getOrNull()
                    if (pos != null) {
                        _state.update {
                            it.copy(
                                positionMs = pos,
                                durationMs = dur ?: it.durationMs,
                                isPlaying = runCatching { mp.isPlaying }.getOrDefault(it.isPlaying)
                            )
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastPersistedAtMs >= POSITION_SAVE_INTERVAL_MS) {
                            lastPersistedAtMs = now
                            _state.value.currentItem?.let { item ->
                                positionStore.savePositionMs(item.id, pos, _state.value.durationMs)
                            }
                        }
                    }
                }
                delay(500L)
            }
        }
    }

    private fun stopProgressTicker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun saveCurrentPositionNow() {
        val current = _state.value
        val item = current.currentItem ?: return
        if (!isPrepared) return
        val pos = currentPlayerPositionMs()
        positionStore.savePositionMs(item.id, pos, current.durationMs)
    }

    private fun releasePlayer() {
        stopProgressTicker()
        isPrepared = false
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            runCatching { mp.setDisplay(null) }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val res = audioManager.requestAudioFocus(focusRequest)
        hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
        hasAudioFocus = false
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        runCatching { context.unregisterReceiver(noisyReceiver) }
        noisyReceiverRegistered = false
    }

    private fun updateMediaSessionState() {
        val s = _state.value
        val stateCode = when {
            !s.hasActiveItem -> AndroidPlaybackState.STATE_STOPPED
            s.isBuffering -> AndroidPlaybackState.STATE_BUFFERING
            s.isPlaying -> AndroidPlaybackState.STATE_PLAYING
            else -> AndroidPlaybackState.STATE_PAUSED
        }
        val actions = AndroidPlaybackState.ACTION_PLAY or
            AndroidPlaybackState.ACTION_PAUSE or
            AndroidPlaybackState.ACTION_PLAY_PAUSE or
            AndroidPlaybackState.ACTION_SEEK_TO or
            AndroidPlaybackState.ACTION_FAST_FORWARD or
            AndroidPlaybackState.ACTION_REWIND or
            AndroidPlaybackState.ACTION_SKIP_TO_NEXT or
            AndroidPlaybackState.ACTION_SKIP_TO_PREVIOUS or
            AndroidPlaybackState.ACTION_STOP

        mediaSession.setPlaybackState(
            AndroidPlaybackState.Builder()
                .setActions(actions)
                .setState(stateCode, s.positionMs, if (s.isPlaying) s.playbackSpeed else 0f)
                .build()
        )
    }

    private fun loadAudioCoverArt(task: DownloadTask) {
        coverArtJob?.cancel()
        _coverArt.value = null
        updateMediaSessionMetadata(task, null)
        coverArtJob = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val embedded = extractEmbeddedAudioCoverBytesSync(context, task.filePath)
                embedded?.let(::decodeBoundedBitmap) ?: task.thumbnailUrl
                    ?.takeIf { it.isNotBlank() }
                    ?.let { url ->
                        runCatching {
                            val result = context.imageLoader.execute(
                                ImageRequest.Builder(context)
                                    .data(url)
                                    .size(MAX_COVER_ART_PX)
                                    .allowHardware(false)
                                    .build()
                            )
                            (result.drawable as? BitmapDrawable)?.bitmap
                        }.getOrNull()
                    }
            }
            if (_state.value.currentItem?.id == task.id) {
                _coverArt.value = bitmap
                updateMediaSessionMetadata(task, bitmap)
            }
        }
    }

    private fun decodeBoundedBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_COVER_ART_PX ||
            bounds.outHeight / sampleSize > MAX_COVER_ART_PX
        ) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun updateMediaSessionMetadata(task: DownloadTask?, bitmap: Bitmap?) {
        if (task == null) {
            mediaSession.setMetadata(null)
            return
        }
        val duration = _state.value.durationMs.takeIf { it > 0L }
            ?: ((task.durationSeconds ?: 0) * 1000L)
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, task.title.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, task.artist.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ALBUM, task.album.orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
        if (bitmap != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
        }
        mediaSession.setMetadata(builder.build())
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private const val MAX_COVER_ART_PX = 512
    }
}
