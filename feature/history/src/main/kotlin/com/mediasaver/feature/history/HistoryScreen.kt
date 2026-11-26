package com.mediasaver.feature.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.ui.R as UiR
import com.mediasaver.core.ui.components.AppSnackbar
import com.mediasaver.core.ui.components.EmptyState
import com.mediasaver.core.ui.components.HistoryRow
import com.mediasaver.core.ui.theme.CardShape
import com.mediasaver.core.ui.theme.ChipShape
import com.mediasaver.core.ui.theme.MediaSaverTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissMessage()
        }
    }

    HistoryScreenContent(
        modifier = modifier,
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onClearAll = { viewModel.showClearAllDialog(true) },
        onOpenMedia = { openMedia(context, it) },
        onShareMedia = { shareMedia(context, it) },
        onDeleteMedia = { viewModel.requestDeleteItem(it) },
        onConfirmDelete = { viewModel.confirmDeleteItem() },
        onCancelDelete = { viewModel.requestDeleteItem(null) },
        onConfirmClearAll = { viewModel.confirmClearAll() },
        onCancelClearAll = { viewModel.showClearAllDialog(false) }
    )
}

@Composable
fun HistoryScreenContent(
    modifier: Modifier = Modifier,
    uiState: HistoryUiState,
    snackbarHostState: SnackbarHostState,
    onClearAll: () -> Unit,
    onOpenMedia: (DownloadTask) -> Unit,
    onShareMedia: (DownloadTask) -> Unit,
    onDeleteMedia: (DownloadTask) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmClearAll: () -> Unit,
    onCancelClearAll: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedPlatform by remember { mutableStateOf<PlatformId?>(null) }

    val haptic = LocalHapticFeedback.current

    // Filter items according to search query and platform selection
    val filteredItems = remember(uiState.completedItems, searchQuery, selectedPlatform) {
        uiState.completedItems.filter { item ->
            val matchesPlatform = selectedPlatform == null || item.platform == selectedPlatform
            val matchesSearch = searchQuery.isBlank() ||
                (item.title?.contains(searchQuery, ignoreCase = true) == true) ||
                (item.url.contains(searchQuery, ignoreCase = true))
            matchesPlatform && matchesSearch
        }
    }

    val todayLabel = stringResource(UiR.string.history_today)
    val yesterdayLabel = stringResource(UiR.string.history_yesterday)
    val earlierLabel = stringResource(UiR.string.history_earlier)

    // Group items by day (Today, Yesterday, Date)
    val groupedItems = remember(filteredItems, todayLabel, yesterdayLabel, earlierLabel) {
        val groups = linkedMapOf<String, MutableList<DownloadTask>>()
        filteredItems.forEach { task ->
            val timestamp = task.completedAt ?: task.createdAt
            val dayHeader = getDayHeader(timestamp, todayLabel, yesterdayLabel, earlierLabel)
            groups.getOrPut(dayHeader) { mutableListOf() }.add(task)
        }
        groups
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // Screen Header: Title + Clear All button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(UiR.string.history_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(UiR.string.history_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (uiState.completedItems.isNotEmpty()) {
                    IconButton(onClick = onClearAll) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = stringResource(UiR.string.history_clear_all),
                            tint = MaterialTheme.colorScheme.error
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
            } else if (uiState.completedItems.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = stringResource(UiR.string.history_empty_title),
                    description = stringResource(UiR.string.history_empty_description),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = stringResource(UiR.string.history_search_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = stringResource(UiR.string.history_clear_search),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Horizontal platform FilterChips: All, YouTube, TikTok, WhatsApp, Instagram
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val platforms = listOf(
                        null to stringResource(UiR.string.history_all),
                        PlatformId.YOUTUBE to "YouTube",
                        PlatformId.TIKTOK to "TikTok",
                        PlatformId.WHATSAPP to "WhatsApp",
                        PlatformId.INSTAGRAM to "Instagram",
                        PlatformId.FACEBOOK to "Facebook"
                    )

                    platforms.forEach { (platform, label) ->
                        val isSelected = selectedPlatform == platform
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedPlatform = if (isSelected) null else platform },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            shape = ChipShape,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                selectedBorderColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // List of HistoryRow grouped by day headers
                if (filteredItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(UiR.string.history_no_matches),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        groupedItems.forEach { (dayHeader, itemsInDay) ->
                            item(key = "header_$dayHeader") {
                                Text(
                                    text = dayHeader,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 4.dp)
                                )
                            }

                            items(
                                items = itemsInDay,
                                key = { it.id }
                            ) { item ->
                                HistoryRow(
                                    task = item,
                                    onOpen = { onOpenMedia(item) },
                                    onShare = { onShareMedia(item) },
                                    onDelete = { onDeleteMedia(item) }
                                )
                            }
                        }
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

    // Confirm Single Delete Dialog
    uiState.itemPendingDelete?.let { _ ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = {
                Text(
                    text = stringResource(UiR.string.history_delete_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(UiR.string.history_delete_dialog_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirmDelete()
                }) {
                    Text(stringResource(UiR.string.history_delete), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) {
                    Text(stringResource(UiR.string.common_cancel))
                }
            }
        )
    }

    // Confirm Clear All Dialog
    if (uiState.showClearAllDialog) {
        AlertDialog(
            onDismissRequest = onCancelClearAll,
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = {
                Text(
                    text = stringResource(UiR.string.history_clear_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(UiR.string.history_clear_dialog_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirmClearAll()
                }) {
                    Text(stringResource(UiR.string.history_clear_all_button), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelClearAll) {
                    Text(stringResource(UiR.string.common_cancel))
                }
            }
        )
    }
}

private fun getDayHeader(
    timestamp: Long,
    todayLabel: String,
    yesterdayLabel: String,
    earlierLabel: String
): String {
    if (timestamp <= 0L) return earlierLabel

    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }

    val isToday = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

    if (isToday) return todayLabel

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

    if (isYesterday) return yesterdayLabel

    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

internal fun resolveMediaMimeType(context: Context, uri: Uri, task: DownloadTask): String {
    task.mimeType?.takeIf { it.isNotBlank() }?.let { return it }
    if (uri.scheme == "content") {
        runCatching { context.contentResolver.getType(uri) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    val ext = task.selectedFormat?.lowercase()?.trim()?.takeIf { it.isNotBlank() }
        ?: task.filePath?.takeUnless { it.startsWith("content://") }?.let { File(it).extension.lowercase().trim() }
    when (ext) {
        "jpg", "jpeg" -> return "image/jpeg"
        "png" -> return "image/png"
        "webp" -> return "image/webp"
        "gif" -> return "image/gif"
        "m4a" -> return "audio/mp4"
        "mp3" -> return "audio/mpeg"
        "aac" -> return "audio/aac"
        "ogg" -> return "audio/ogg"
        "mp4", "mpeg-4" -> return "video/mp4"
        "webm" -> return "video/webm"
    }
    val path = task.filePath.orEmpty()
    return when {
        "/images/" in path -> "image/*"
        "/audio/" in path -> "audio/*"
        "/video/" in path -> "video/*"
        else -> "*/*"
    }
}

private fun openMedia(context: Context, task: DownloadTask) {
    val path = task.filePath ?: return
    try {
        val uri = if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            val file = File(path)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolveMediaMimeType(context, uri, task))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open media"))
    } catch (_: Exception) {}
}

private fun shareMedia(context: Context, task: DownloadTask) {
    val path = task.filePath ?: return
    try {
        val uri = if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            val file = File(path)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = resolveMediaMimeType(context, uri, task)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share media"))
    } catch (_: Exception) {}
}

@Preview(name = "History Screen - Dark", showBackground = true)
@Composable
private fun HistoryScreenDarkPreview() {
    MediaSaverTheme(darkTheme = true) {
        HistoryScreenContent(
            uiState = HistoryUiState(
                completedItems = listOf(
                    DownloadTask(
                        id = "1",
                        url = "https://youtube.com/watch?v=1",
                        platform = PlatformId.YOUTUBE,
                        mediaId = "1",
                        title = "Preview of Saved YouTube Video",
                        downloadedBytes = 14_500_000L,
                        totalBytes = 14_500_000L,
                        completedAt = System.currentTimeMillis()
                    ),
                    DownloadTask(
                        id = "2",
                        url = "https://tiktok.com/@user/1",
                        platform = PlatformId.TIKTOK,
                        mediaId = "2",
                        title = "TikTok Comedy Skit",
                        downloadedBytes = 5_200_000L,
                        totalBytes = 5_200_000L,
                        completedAt = System.currentTimeMillis() - 86400000L
                    )
                )
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onClearAll = {},
            onOpenMedia = {},
            onShareMedia = {},
            onDeleteMedia = {},
            onConfirmDelete = {},
            onCancelDelete = {},
            onConfirmClearAll = {},
            onCancelClearAll = {}
        )
    }
}

@Preview(name = "History Screen - Light", showBackground = true)
@Composable
private fun HistoryScreenLightPreview() {
    MediaSaverTheme(darkTheme = false) {
        HistoryScreenContent(
            uiState = HistoryUiState(completedItems = emptyList()),
            snackbarHostState = remember { SnackbarHostState() },
            onClearAll = {},
            onOpenMedia = {},
            onShareMedia = {},
            onDeleteMedia = {},
            onConfirmDelete = {},
            onCancelDelete = {},
            onConfirmClearAll = {},
            onCancelClearAll = {}
        )
    }
}
