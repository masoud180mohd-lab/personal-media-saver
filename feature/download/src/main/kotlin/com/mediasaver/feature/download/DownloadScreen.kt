package com.mediasaver.feature.download

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediasaver.core.model.DownloadProgressInfo
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.ui.R as UiR
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
        onRetryDownload = { viewModel.retryDownload(it) },
        onRemoveDownload = { viewModel.removeDownload(it) },
        onPauseDownload = { viewModel.pauseDownload(it) },
        onResumeDownload = { viewModel.resumeDownload(it) },
        onPauseAll = { viewModel.pauseAll() },
        onResumeAll = { viewModel.resumeAll() },
        onCancelAll = { viewModel.cancelAll() },
        onRetryAllFailed = { viewModel.retryAllFailed() }
    )
}

@Composable
fun DownloadScreenContent(
    modifier: Modifier = Modifier,
    uiState: DownloadUiState,
    snackbarHostState: SnackbarHostState,
    onCancelDownload: (String) -> Unit,
    onRetryDownload: (String) -> Unit,
    onRemoveDownload: (String) -> Unit = {},
    onPauseDownload: (String) -> Unit = {},
    onResumeDownload: (String) -> Unit = {},
    onPauseAll: () -> Unit = {},
    onResumeAll: () -> Unit = {},
    onCancelAll: () -> Unit = {},
    onRetryAllFailed: () -> Unit = {}
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
                        text = stringResource(UiR.string.downloads_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(UiR.string.downloads_subtitle),
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
                    title = stringResource(UiR.string.downloads_empty_title),
                    description = stringResource(UiR.string.downloads_empty_description),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val activeDownloadIds = uiState.activeTasks
                    .asSequence()
                    .filter { it.status == DownloadStatus.DOWNLOADING }
                    .map { it.id }
                    .toSet()
                val pausedCount = uiState.activeTasks.count { it.status == DownloadStatus.PAUSED }
                val hasActive = uiState.activeTasks.any { it.status.isActive }
                val hasPausable = uiState.activeTasks.any { it.status.isPausable }
                val hasPaused = pausedCount > 0
                val hasFailed = uiState.activeTasks.any { it.status == DownloadStatus.FAILED }
                val hasNonTerminal = uiState.activeTasks.any { !it.status.isTerminal }

                // Sticky summary row: live speed + transferring count + paused count
                if (hasActive || hasPaused) {
                    val totalSpeed = uiState.liveProgressMap.entries.sumOf { (id, info) ->
                        if (id in activeDownloadIds) info.speedBytesPerSec else 0L
                    }
                    val downloadingCount = activeDownloadIds.size

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasActive) {
                            Surface(
                                shape = ChipShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = stringResource(UiR.string.downloads_total_speed, formatSpeed(totalSpeed)),
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
                                    text = stringResource(UiR.string.downloads_transferring_count, downloadingCount),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        if (pausedCount > 0) {
                            Surface(
                                shape = ChipShape,
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = stringResource(UiR.string.downloads_paused_count, pausedCount),
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontFeatureSettings = "tnum"
                                    ),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // Bulk Queue Controls row
                if (hasPausable || hasPaused || hasFailed || hasNonTerminal) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (hasPausable) {
                            FilledTonalButton(
                                onClick = onPauseAll,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(UiR.string.downloads_pause_all),
                                    modifier = Modifier.padding(start = 6.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        if (hasPaused) {
                            FilledTonalButton(
                                onClick = onResumeAll,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(UiR.string.downloads_resume_all),
                                    modifier = Modifier.padding(start = 6.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        if (hasFailed) {
                            FilledTonalButton(
                                onClick = onRetryAllFailed,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(UiR.string.downloads_retry_failed),
                                    modifier = Modifier.padding(start = 6.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        if (hasNonTerminal) {
                            OutlinedButton(
                                onClick = onCancelAll,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(UiR.string.downloads_cancel_all),
                                    modifier = Modifier.padding(start = 6.dp),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
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
                            onRetry = { onRetryDownload(task.id) },
                            onRemove = { onRemoveDownload(task.id) },
                            onPause = { onPauseDownload(task.id) },
                            onResume = { onResumeDownload(task.id) }
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
