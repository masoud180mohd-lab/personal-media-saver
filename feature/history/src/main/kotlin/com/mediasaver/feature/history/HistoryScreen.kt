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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
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

    // Group items by day (Today, Yesterday, Date)
    val groupedItems = remember(filteredItems) {
        val groups = linkedMapOf<String, MutableList<DownloadTask>>()
        filteredItems.forEach { task ->
            val timestamp = task.completedAt ?: task.createdAt
            val dayHeader = getDayHeader(timestamp)
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
                        text = "History",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Saved media stored locally on your device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (uiState.completedItems.isNotEmpty()) {
                    IconButton(onClick = onClearAll) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteSweep,
                            contentDescription = "Clear all history",
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
                    title = "No Download History",
                    description = "Media you finish downloading will be archived here for offline playback and sharing.",
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
                            text = "Search saved media...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = "Clear search",
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
                        null to "All",
                        PlatformId.YOUTUBE to "YouTube",
                        PlatformId.TIKTOK to "TikTok",
                        PlatformId.WHATSAPP to "WhatsApp",
                        PlatformId.INSTAGRAM to "Instagram"
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
                            text = "No matching items found",
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
    uiState.itemPendingDelete?.let { pendingItem ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = {
                Text(
                    text = "Delete from history?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Remove '${pendingItem.title ?: "this item"}' from your download history? (File remains in your gallery).",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirmDelete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) {
                    Text("Cancel")
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
                    text = "Clear all history?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will remove all completed download logs from the app. Downloaded media files in your storage will not be deleted.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirmClearAll()
                }) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelClearAll) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun getDayHeader(timestamp: Long): String {
    if (timestamp <= 0L) return "Earlier"

    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }

    val isToday = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

    if (isToday) return "Today"

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    val isYesterday = yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)

    if (isYesterday) return "Yesterday"

    val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
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
            setDataAndType(uri, task.mimeType ?: "video/*")
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
            type = task.mimeType ?: "video/*"
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
