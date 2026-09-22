package com.mediasaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.ui.theme.CardShape
import com.mediasaver.core.ui.theme.ChipShape
import com.mediasaver.core.ui.theme.MediaCardShape
import com.mediasaver.core.ui.theme.MediaSaverTheme
import com.mediasaver.core.ui.util.shimmerBackground
import java.util.Locale

import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.PlayArrow
import com.mediasaver.core.ui.theme.ThumbShape

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow

@Composable
fun MediaPreviewCard(
    mediaInfo: MediaInfo,
    availableStreams: List<StreamInfo> = emptyList(),
    onActiveSlideChanged: (StreamInfo) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isCarousel = availableStreams.size > 1 && availableStreams.all { it.quality?.startsWith("Slide ") == true }

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CardShape
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 16:9 thumbnail with rounded 16dp corners, Coil crossfade & shimmer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(ThumbShape)
            ) {
                if (isCarousel) {
                    val pagerState = rememberPagerState { availableStreams.size }
                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.currentPage }.collect { page ->
                            onActiveSlideChanged(availableStreams[page])
                        }
                    }
                    
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        pageSpacing = 8.dp,
                        flingBehavior = PagerDefaults.flingBehavior(
                            state = pagerState,
                            snapAnimationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    ) { page ->
                        val stream = availableStreams[page]
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(stream.url) // Use the stream URL directly for the slide image/video frame
                                .crossfade(true)
                                .build(),
                            contentDescription = "Slide ${page + 1} of ${availableStreams.size}",
                            contentScale = ContentScale.Crop,
                            loading = { Box(modifier = Modifier.fillMaxSize().shimmerBackground()) },
                            error = {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = mediaInfo.platform.getIcon(),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                        
                        // Play icon overlay if it's a video
                        if (stream.format == "mp4" || stream.mimeType?.startsWith("video") == true) {
                            Surface(
                                shape = CircleShape,
                                color = Color.Black.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
                    
                    // Top-end Slide indicator
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    ) {
                        Surface(
                            shape = ChipShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                        ) {
                            Text(
                                text = "${pagerState.currentPage + 1}/${availableStreams.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(mediaInfo.thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = mediaInfo.title,
                        contentScale = ContentScale.Crop,
                        loading = { Box(modifier = Modifier.fillMaxSize().shimmerBackground()) },
                        error = {
                            Box(
                                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = mediaInfo.platform.getIcon(),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Center Play overlay icon
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.55f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                // Top-start PlatformBadge overlay
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                ) {
                    PlatformBadge(platformId = mediaInfo.platform)
                }

                // Bottom-end Duration Chip overlay (scrim background)
                mediaInfo.durationSeconds?.let { seconds ->
                    if (seconds > 0) {
                        Surface(
                            shape = ChipShape,
                            color = Color.Black.copy(alpha = 0.72f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = formatDuration(seconds),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Title (2-line clamp, titleMedium)
            Text(
                text = mediaInfo.title ?: "Untitled Media",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Source URL (labelMedium, onSurfaceVariant)
            Text(
                text = mediaInfo.sourceUrl,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun QualityChipRow(
    availableStreams: List<StreamInfo>,
    selectedStream: StreamInfo?,
    onSelectStream: (StreamInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    if (availableStreams.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Quality & Format",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            availableStreams.forEach { stream ->
                val isSelected = stream == selectedStream
                val size = stream.fileSize
                val labelText = buildString {
                    append(stream.quality ?: stream.format ?: "Standard")
                    if (size != null && size > 0) {
                        append(" (")
                        append(formatFileSize(size))
                        append(")")
                    }
                    if (stream.isAudioOnly) append(" [Audio]")
                }

                FilterChip(
                    selected = isSelected,
                    onClick = { onSelectStream(stream) },
                    label = {
                        Text(
                            text = labelText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Selected",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null,
                    shape = ChipShape,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = MaterialTheme.colorScheme.outlineVariant,
                        selectedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

private fun formatDuration(seconds: Int): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

@Preview(name = "Dark Theme", showBackground = true)
@Composable
private fun MediaPreviewCardDarkPreview() {
    MediaSaverTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            MediaPreviewCard(
                mediaInfo = MediaInfo(
                    id = "1",
                    platform = PlatformId.YOUTUBE,
                    title = "Sample Video Title with High Definition Visuals and Deep Sound",
                    thumbnailUrl = null,
                    durationSeconds = 245,
                    sourceUrl = "https://youtube.com/watch?v=dQw4w9WgXcQ"
                )
            )
        }
    }
}

@Preview(name = "Light Theme", showBackground = true)
@Composable
private fun MediaPreviewCardLightPreview() {
    MediaSaverTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            MediaPreviewCard(
                mediaInfo = MediaInfo(
                    id = "1",
                    platform = PlatformId.YOUTUBE,
                    title = "Sample Video Title with High Definition Visuals and Deep Sound",
                    thumbnailUrl = null,
                    durationSeconds = 245,
                    sourceUrl = "https://youtube.com/watch?v=dQw4w9WgXcQ"
                )
            )
        }
    }
}
