package com.mediasaver.feature.download

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediasaver.core.model.DownloadProgressInfo
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.ui.components.AppSnackbar
import com.mediasaver.core.ui.components.DownloadProgressCard
import com.mediasaver.core.ui.components.EmptyState
import com.mediasaver.core.ui.theme.ChipShape
import com.mediasaver.core.ui.theme.MediaSaverTheme
import java.util.Locale

@Composable
fun DownloadScreen(
    modifier: Modifier = Modifier,
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissMessage()
        }
    }

    DownloadScreenContent(
        modifier = modifier,
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onCancelDownload = { viewModel.cancelDownload(it) },
        onRetryDownload = { viewModel.retryDownload(it) }
    )
}

@Composable
fun DownloadScreenContent(
    modifier: Modifier = Modifier,
    uiState: DownloadUiState,
    snackbarHostState: SnackbarHostState,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // Screen Header: "Active Downloads" + count chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Active Downloads",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Ongoing media processing and transfers",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (uiState.activeTasks.isNotEmpty()) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${uiState.activeTasks.size}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.activeTasks.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.Download,
                    title = "No Active Downloads",
                    description = "When you start downloading videos or audio, track their progress, speed, and ETA right here.",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Sticky summary row when active: total speed + active count chips
                val totalSpeed = uiState.liveProgressMap.values.sumOf { it.speedBytesPerSec }
                val downloadingCount = uiState.activeTasks.count { it.status == DownloadStatus.DOWNLOADING }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = ChipShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = "Total Speed: ${formatSpeed(totalSpeed)}",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFeatureSettings = "tnum"
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        shape = ChipShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = "$downloadingCount transferring",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFeatureSettings = "tnum"
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // List of DownloadProgressCard (12dp gaps)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = uiState.activeTasks,
                        key = { it.id }
                    ) { task ->
                        val liveProgress = uiState.liveProgressMap[task.id]
                        DownloadProgressCard(
                            task = task,
                            liveProgress = liveProgress,
                            onCancel = { onCancelDownload(task.id) },
                            onRetry = { onRetryDownload(task.id) }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { snackbarData ->
            AppSnackbar(snackbarData = snackbarData)
        }
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

@Preview(name = "Download Screen - Dark", showBackground = true)
@Composable
private fun DownloadScreenDarkPreview() {
    MediaSaverTheme(darkTheme = true) {
        DownloadScreenContent(
            uiState = DownloadUiState(
                activeTasks = listOf(
                    DownloadTask(
                        id = "1",
                        url = "https://youtube.com",
                        platform = PlatformId.YOUTUBE,
                        mediaId = "1",
                        title = "Sample Active Download Video in Full HD",
                        status = DownloadStatus.DOWNLOADING,
                        totalBytes = 80_000_000L,
                        downloadedBytes = 48_000_000L
                    )
                ),
                liveProgressMap = mapOf(
                    "1" to DownloadProgressInfo(
                        downloadId = "1",
                        bytesDownloaded = 48_000_000L,
                        totalBytes = 80_000_000L,
                        progressPercent = 60,
                        speedBytesPerSec = 4_200_000L,
                        etaSeconds = 7L
                    )
                )
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onCancelDownload = {},
            onRetryDownload = {}
        )
    }
}

@Preview(name = "Download Screen - Light", showBackground = true)
@Composable
private fun DownloadScreenLightPreview() {
    MediaSaverTheme(darkTheme = false) {
        DownloadScreenContent(
            uiState = DownloadUiState(activeTasks = emptyList()),
            snackbarHostState = remember { SnackbarHostState() },
            onCancelDownload = {},
            onRetryDownload = {}
        )
    }
}
