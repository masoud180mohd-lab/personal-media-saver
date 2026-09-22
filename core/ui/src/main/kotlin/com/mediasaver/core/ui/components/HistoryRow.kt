package com.mediasaver.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.ui.theme.CardShape
import com.mediasaver.core.ui.theme.MediaSaverTheme
import com.mediasaver.core.ui.theme.ThumbShape
import com.mediasaver.core.ui.util.shimmerBackground
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryRow(
    task: DownloadTask,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CardShape
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 56dp rounded thumb (ThumbShape 16dp)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(ThumbShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            ) {
                val imageModel = task.filePath?.let { File(it) } ?: task.thumbnailUrl
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageModel)
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

            // Title + meta line (platform • date • size)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title ?: "Downloaded Media",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(3.dp))

                val metaString = buildString {
                    append(task.platform.getDisplayName())
                    val dateFormatted = formatHistoricalDate(task.completedAt ?: task.createdAt)
                    if (dateFormatted.isNotBlank()) {
                        append(" • ")
                        append(dateFormatted)
                    }
                    val size = task.totalBytes?.takeIf { it > 0 } ?: task.downloadedBytes.takeIf { it > 0 }
                    if (size != null) {
                        append(" • ")
                        append(formatFileSize(size))
                    }
                }

                Text(
                    text = metaString,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Trailing overflow menu
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    DropdownMenuItem(
                        text = { Text("Open") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onOpen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

private fun formatHistoricalDate(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.1f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

@Preview(name = "Dark Theme", showBackground = true)
@Composable
private fun HistoryRowDarkPreview() {
    MediaSaverTheme(darkTheme = true) {
        Column(modifier = Modifier.padding(16.dp)) {
            HistoryRow(
                task = DownloadTask(
                    id = "1",
                    url = "https://youtube.com/watch?v=123",
                    platform = PlatformId.YOUTUBE,
                    mediaId = "123",
                    title = "Sample History Video Title",
                    downloadedBytes = 18_400_000L,
                    totalBytes = 18_400_000L,
                    completedAt = System.currentTimeMillis()
                ),
                onOpen = {},
                onShare = {},
                onDelete = {}
            )
        }
    }
}

@Preview(name = "Light Theme", showBackground = true)
@Composable
private fun HistoryRowLightPreview() {
    MediaSaverTheme(darkTheme = false) {
        Column(modifier = Modifier.padding(16.dp)) {
            HistoryRow(
                task = DownloadTask(
                    id = "1",
                    url = "https://youtube.com/watch?v=123",
                    platform = PlatformId.YOUTUBE,
                    mediaId = "123",
                    title = "Sample History Video Title",
                    downloadedBytes = 18_400_000L,
                    totalBytes = 18_400_000L,
                    completedAt = System.currentTimeMillis()
                ),
                onOpen = {},
                onShare = {},
                onDelete = {}
            )
        }
    }
}
