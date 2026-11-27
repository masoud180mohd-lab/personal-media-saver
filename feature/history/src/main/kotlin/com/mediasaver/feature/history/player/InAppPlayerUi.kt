package com.mediasaver.feature.history.player

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.mediasaver.core.model.PlaybackState
import com.mediasaver.core.model.SUPPORTED_PLAYBACK_SPEEDS
import com.mediasaver.core.model.formatPlaybackTime
import com.mediasaver.core.ui.R as UiR
import com.mediasaver.core.ui.theme.CardShape
import com.mediasaver.core.ui.theme.ChipShape
import com.mediasaver.core.ui.theme.ThumbShape
import com.mediasaver.core.ui.util.rememberMediaCoverModel

@Composable
fun MiniAudioPlayerBar(
    state: PlaybackState,
    onExpand: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val item = state.currentItem ?: return
    val progress = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MediaCoverThumb(
                    filePath = item.filePath,
                    thumbnailUrl = item.thumbnailUrl,
                    mimeType = item.mimeType,
                    selectedFormat = item.selectedFormat,
                    shape = ThumbShape,
                    fallbackIconSize = 22.dp,
                    modifier = Modifier.size(44.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title?.takeIf { it.isNotBlank() }
                            ?: stringResource(UiR.string.player_video_title_fallback),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val subtitle = buildList {
                        item.artist?.takeIf { it.isNotBlank() }?.let(::add)
                        add("${formatPlaybackTime(state.positionMs)} / ${formatPlaybackTime(state.durationMs)}")
                        if (state.playbackSpeed != 1.0f) add("${state.playbackSpeed}x")
                    }.joinToString(" • ")
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onTogglePlayPause) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(
                                if (state.isPlaying) UiR.string.player_pause else UiR.string.player_play
                            ),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(UiR.string.player_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullAudioPlayerSheet(
    state: PlaybackState,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onOpenExternal: () -> Unit
) {
    if (state.currentItem == null) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        FullAudioPlayerContent(
            state = state,
            onTogglePlayPause = onTogglePlayPause,
            onSeekTo = onSeekTo,
            onRewind10 = onRewind10,
            onForward10 = onForward10,
            onPrevious = onPrevious,
            onNext = onNext,
            onSelectSpeed = onSelectSpeed,
            onOpenExternal = onOpenExternal
        )
    }
}

@Composable
fun FullAudioPlayerContent(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onOpenExternal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val item = state.currentItem ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(UiR.string.player_now_playing),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        MediaCoverThumb(
            filePath = item.filePath,
            thumbnailUrl = item.thumbnailUrl,
            mimeType = item.mimeType,
            selectedFormat = item.selectedFormat,
            shape = CardShape,
            fallbackIconSize = 64.dp,
            modifier = Modifier
                .size(216.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = CardShape
                )
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = item.title?.takeIf { it.isNotBlank() }
                    ?: stringResource(UiR.string.player_video_title_fallback),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            val metaText = listOfNotNull(
                item.artist?.takeIf { it.isNotBlank() },
                item.album?.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
            if (metaText.isNotEmpty()) {
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.errorMessage?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        SeekProgressBar(
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            onSeekTo = onSeekTo,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevious, enabled = state.hasPrevious || state.positionMs > 0L) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = stringResource(UiR.string.player_previous)
                )
            }
            IconButton(onClick = onRewind10) {
                Icon(
                    imageVector = Icons.Rounded.Replay10,
                    contentDescription = stringResource(UiR.string.player_rewind_10),
                    modifier = Modifier.size(30.dp)
                )
            }
            FilledIconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier.size(60.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) UiR.string.player_pause else UiR.string.player_play
                        ),
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            IconButton(onClick = onForward10) {
                Icon(
                    imageVector = Icons.Rounded.Forward10,
                    contentDescription = stringResource(UiR.string.player_forward_10),
                    modifier = Modifier.size(30.dp)
                )
            }
            IconButton(onClick = onNext, enabled = state.hasNext) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = stringResource(UiR.string.player_next)
                )
            }
        }

        SpeedSelectorRow(
            selectedSpeed = state.playbackSpeed,
            onSelectSpeed = onSelectSpeed
        )

        OutlinedButton(
            onClick = onOpenExternal,
            shape = ChipShape
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(UiR.string.player_open_external))
        }
    }
}

@Composable
fun InAppVideoPlayerDialog(
    state: PlaybackState,
    aspectRatio: Float,
    onAttachSurface: (SurfaceHolder?) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onRewind10: () -> Unit,
    onForward10: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onOpenExternal: () -> Unit,
    onClose: () -> Unit
) {
    val item = state.currentItem ?: return
    var showControls by remember { mutableStateOf(true) }
    val hostStatusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val hostNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val topInset = maxOf(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), hostStatusTop)
        val bottomInset = maxOf(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), hostNavBottom)

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showControls = !showControls },
                contentAlignment = Alignment.Center
            ) {
                VideoSurfaceView(
                    aspectRatio = aspectRatio,
                    onAttachSurface = onAttachSurface
                )

                if (state.isBuffering) {
                    CircularProgressIndicator(color = Color.White)
                }

                AnimatedVisibility(
                    visible = showControls,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                                    )
                                )
                                .padding(top = topInset + 8.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = item.title?.takeIf { it.isNotBlank() }
                                    ?: stringResource(UiR.string.player_video_title_fallback),
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onOpenExternal) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = stringResource(UiR.string.player_open_external),
                                    tint = Color.White
                                )
                            }
                            IconButton(onClick = onClose) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(UiR.string.player_close),
                                    tint = Color.White
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onRewind10, modifier = Modifier.size(52.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Replay10,
                                    contentDescription = stringResource(UiR.string.player_rewind_10),
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            FilledIconButton(
                                onClick = onTogglePlayPause,
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(
                                        if (state.isPlaying) UiR.string.player_pause else UiR.string.player_play
                                    ),
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            IconButton(onClick = onForward10, modifier = Modifier.size(52.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Forward10,
                                    contentDescription = stringResource(UiR.string.player_forward_10),
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                    )
                                )
                                .padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 24.dp,
                                    bottom = maxOf(bottomInset + 16.dp, 36.dp)
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.errorMessage?.let { error ->
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            SeekProgressBar(
                                positionMs = state.positionMs,
                                durationMs = state.durationMs,
                                onSeekTo = onSeekTo,
                                labelColor = Color.White
                            )
                            SpeedSelectorRow(
                                selectedSpeed = state.playbackSpeed,
                                onSelectSpeed = onSelectSpeed,
                                labelColor = Color.White.copy(alpha = 0.9f),
                                isDarkOverlay = true
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoSurfaceView(
    aspectRatio: Float,
    onAttachSurface: (SurfaceHolder?) -> Unit
) {
    val safeRatio = aspectRatio.coerceIn(0.4f, 2.5f)
    DisposableEffect(Unit) {
        onDispose { onAttachSurface(null) }
    }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(safeRatio),
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onAttachSurface(holder)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        onAttachSurface(holder)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        onAttachSurface(null)
                    }
                })
            }
        }
    )
}

@Composable
private fun SeekProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    labelColor: Color
) {
    val maxDuration = durationMs.coerceAtLeast(1L).toFloat()
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember(positionMs, durationMs) {
        mutableFloatStateOf((positionMs.toFloat() / maxDuration).coerceIn(0f, 1f))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = dragFraction,
            onValueChange = { fraction ->
                isDragging = true
                dragFraction = fraction
            },
            onValueChangeFinished = {
                isDragging = false
                onSeekTo((dragFraction * maxDuration).toLong())
            },
            enabled = durationMs > 0L
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val shownPos = if (isDragging) (dragFraction * maxDuration).toLong() else positionMs
            Text(
                text = formatPlaybackTime(shownPos),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
            Text(
                text = formatPlaybackTime(durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = labelColor
            )
        }
    }
}

@Composable
private fun SpeedSelectorRow(
    selectedSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    isDarkOverlay: Boolean = false
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(UiR.string.player_speed),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = labelColor
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SUPPORTED_PLAYBACK_SPEEDS.forEach { speed ->
                val selected = kotlin.math.abs(selectedSpeed - speed) < 0.01f
                val label = if (speed == 1.0f || speed == 2.0f) "${speed.toInt()}x" else "${speed}x"
                val containerColor = when {
                    selected -> MaterialTheme.colorScheme.primary
                    isDarkOverlay -> Color.White.copy(alpha = 0.16f)
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                }
                val contentColor = when {
                    selected -> MaterialTheme.colorScheme.onPrimary
                    isDarkOverlay -> Color.White
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val borderColor = when {
                    selected -> MaterialTheme.colorScheme.primary
                    isDarkOverlay -> Color.White.copy(alpha = 0.40f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                }
                Surface(
                    selected = selected,
                    onClick = { onSelectSpeed(speed) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp),
                    shape = ChipShape,
                    color = containerColor,
                    contentColor = contentColor,
                    border = BorderStroke(1.dp, borderColor)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaCoverThumb(
    filePath: String?,
    thumbnailUrl: String?,
    mimeType: String? = null,
    selectedFormat: String? = null,
    shape: Shape = ThumbShape,
    fallbackIconSize: Dp = 24.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        val cleanThumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() }
        val resolvedModel = rememberMediaCoverModel(
            filePath = filePath,
            thumbnailUrl = cleanThumbnailUrl,
            mimeType = mimeType,
            selectedFormat = selectedFormat
        )
        var fallbackToThumbnail by remember(filePath, cleanThumbnailUrl, mimeType, selectedFormat) {
            mutableStateOf(false)
        }
        val imageModel: Any? = if (fallbackToThumbnail && cleanThumbnailUrl != null) {
            cleanThumbnailUrl
        } else {
            resolvedModel
        }
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(imageModel)
                .crossfade(true)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = {
                if (!fallbackToThumbnail && cleanThumbnailUrl != null && imageModel != cleanThumbnailUrl) {
                    fallbackToThumbnail = true
                }
            },
            modifier = Modifier.fillMaxSize(),
            error = {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(fallbackIconSize)
                    )
                }
            }
        )
    }
}
