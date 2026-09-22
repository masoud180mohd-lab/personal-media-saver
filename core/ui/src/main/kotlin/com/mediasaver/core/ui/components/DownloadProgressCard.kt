package com.mediasaver.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.mediasaver.core.model.DownloadProgressInfo
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.ui.theme.CardShape
import com.mediasaver.core.ui.theme.ChipShape
import com.mediasaver.core.ui.theme.MediaSaverTheme
import com.mediasaver.core.ui.theme.ThumbShape
import com.mediasaver.core.ui.util.rememberIsReduceMotionEnabled
import com.mediasaver.core.ui.util.shimmerBackground
import java.util.Locale

@Composable
fun DownloadProgressCard(
    task: DownloadTask,
    liveProgress: DownloadProgressInfo?,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFailed = task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED
    val isReduceMotion = rememberIsReduceMotionEnabled()

    val containerColor = if (isFailed) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    val borderColor = if (isFailed) {
        MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    // Calculate progress fraction
    val liveTotal = liveProgress?.totalBytes
    val taskTotal = task.totalBytes
    val rawProgressFraction = when {
        liveProgress != null && liveTotal != null && liveTotal > 0 -> {
            (liveProgress.bytesDownloaded.toFloat() / liveTotal.toFloat()).coerceIn(0f, 1f)
        }
        taskTotal != null && taskTotal > 0 -> {
            (task.downloadedBytes.toFloat() / taskTotal.toFloat()).coerceIn(0f, 1f)
        }
        else -> null
    }

    val animatedProgress by animateFloatAsState(
        targetValue = rawProgressFraction ?: 0f,
        animationSpec = if (isReduceMotion) tween(0) else tween(300),
        label = "downloadProgressAnimation"
    )

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = borderColor, shape = CardShape)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top row: 56dp rounded thumb + title + status chip + action icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 56dp thumbnail (16dp rounded thumb)
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(ThumbShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(task.thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .shimmerBackground()
                            )
                        },
                        error = {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = task.platform.getIcon(),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Title + Status Chip
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.title ?: "Processing download...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    StatusPillChip(status = task.status)
                }

                // Trailing Action Button: Cancel (close icon) or Retry (refresh icon)
                if (isFailed) {
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Retry",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Cancel",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Middle: linear progress (primary, rounded, animated)
            if (task.status.isActive) {
                if (rawProgressFraction != null) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round
                    )
                }

                // Bottom row meta chips: percent • speed • ETA • size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val percent = liveProgress?.progressPercent ?: ((rawProgressFraction ?: 0f) * 100).toInt()
                    MetaChip(text = "$percent%")

                    if (liveProgress != null && liveProgress.speedBytesPerSec > 0) {
                        MetaChip(text = formatSpeed(liveProgress.speedBytesPerSec))
                    }

                    val eta = liveProgress?.etaSeconds
                    if (eta != null && eta > 0) {
                        MetaChip(text = "ETA ${formatEta(eta)}")
                    }

                    val totalBytes = liveTotal ?: taskTotal
                    if (totalBytes != null && totalBytes > 0) {
                        val downloaded = liveProgress?.bytesDownloaded ?: task.downloadedBytes
                        MetaChip(text = "${formatFileSize(downloaded)} / ${formatFileSize(totalBytes)}")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPillChip(status: DownloadStatus) {
    val (label, containerColor, contentColor) = when (status) {
        DownloadStatus.QUEUED -> Triple("Queued", MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurfaceVariant)
        DownloadStatus.EXTRACTING -> Triple("Resolving", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        DownloadStatus.DOWNLOADING -> Triple("Downloading", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        DownloadStatus.MUXING -> Triple("Muxing Tracks", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        DownloadStatus.COMPLETED -> Triple("Finished", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)
        DownloadStatus.FAILED -> Triple("Failed", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error)
        DownloadStatus.CANCELLED -> Triple("Cancelled", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Surface(
        shape = CircleShape,
        color = containerColor
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        shape = ChipShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFeatureSettings = "tnum"
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatSpeed(bytesPerSec: Long): String {
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB/s", mb)
    } else {
        String.format(Locale.US, "%.0f KB/s", kb)
    }
}

private fun formatEta(seconds: Long): String {
    return if (seconds >= 60) {
        "${seconds / 60}m ${seconds % 60}s"
    } else {
        "${seconds}s"
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
private fun DownloadProgressCardDarkPreview() {
    MediaSaverTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            DownloadProgressCard(
                task = DownloadTask(
                    id = "sample",
                    url = "https://example.com",
                    platform = PlatformId.YOUTUBE,
                    mediaId = "123",
                    title = "Sample Video Download Progress Demo",
                    status = DownloadStatus.DOWNLOADING,
                    totalBytes = 54_000_000L,
                    downloadedBytes = 24_000_000L
                ),
                liveProgress = DownloadProgressInfo(
                    downloadId = "sample",
                    bytesDownloaded = 24_000_000L,
                    totalBytes = 54_000_000L,
                    progressPercent = 44,
                    speedBytesPerSec = 3_400_000L,
                    etaSeconds = 9L
                ),
                onCancel = {},
                onRetry = {}
            )
        }
    }
}

@Preview(name = "Light Theme", showBackground = true)
@Composable
private fun DownloadProgressCardLightPreview() {
    MediaSaverTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            DownloadProgressCard(
                task = DownloadTask(
                    id = "sample",
                    url = "https://example.com",
                    platform = PlatformId.YOUTUBE,
                    mediaId = "123",
                    title = "Sample Video Download Progress Demo",
                    status = DownloadStatus.DOWNLOADING,
                    totalBytes = 54_000_000L,
                    downloadedBytes = 24_000_000L
                ),
                liveProgress = DownloadProgressInfo(
                    downloadId = "sample",
                    bytesDownloaded = 24_000_000L,
                    totalBytes = 54_000_000L,
                    progressPercent = 44,
                    speedBytesPerSec = 3_400_000L,
                    etaSeconds = 9L
                ),
                onCancel = {},
                onRetry = {}
            )
        }
    }
}
