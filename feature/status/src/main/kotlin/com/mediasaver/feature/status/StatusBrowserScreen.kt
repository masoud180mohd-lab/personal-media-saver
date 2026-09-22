package com.mediasaver.feature.status

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediasaver.core.model.StatusMediaType
import com.mediasaver.core.model.WhatsAppStatus
import com.mediasaver.core.ui.components.AppSnackbar
import com.mediasaver.core.ui.components.EmptyState
import com.mediasaver.core.ui.components.StatusCard
import com.mediasaver.core.ui.theme.CardShape
import com.mediasaver.core.ui.theme.ChipShape
import com.mediasaver.core.ui.theme.MediaSaverTheme

@Composable
fun StatusBrowserScreen(
    modifier: Modifier = Modifier,
    viewModel: StatusViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // SAF Directory picker launcher
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            viewModel.saveStatusUri(uri.toString())
        }
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissMessage()
        }
    }

    StatusBrowserScreenContent(
        modifier = modifier,
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onGrantAccess = {
            val targetPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"
            } else {
                "WhatsApp/Media/.Statuses"
            }
            val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:$targetPath"
                )
            } else {
                null
            }
            safLauncher.launch(initialUri)
        },
        onRefresh = { viewModel.scanStatuses() },
        onSaveStatus = { viewModel.saveStatus(it) },
        onSetFilter = { viewModel.setFilter(it) }
    )
}

@Composable
fun StatusBrowserScreenContent(
    modifier: Modifier = Modifier,
    uiState: StatusUiState,
    snackbarHostState: SnackbarHostState,
    onGrantAccess: () -> Unit,
    onRefresh: () -> Unit,
    onSaveStatus: (WhatsAppStatus) -> Unit,
    onSetFilter: (StatusFilter) -> Unit
) {
    var selectedStatusIds by remember { mutableStateOf(setOf<String>()) }
    var filterSavedOnly by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val displayedStatuses = remember(uiState.filteredStatuses, filterSavedOnly) {
        if (filterSavedOnly) {
            uiState.filteredStatuses.filter { it.isSaved }
        } else {
            uiState.filteredStatuses
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            // Screen Header: Title + Refresh
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Statuses",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "View and save WhatsApp status updates",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (uiState.isPermissionGranted) {
                    IconButton(onClick = onRefresh, enabled = !uiState.isScanning) {
                        if (uiState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            if (!uiState.isPermissionGranted) {
                // Hero card with shield icon, concise 3-line explanation, filled CTA "Grant Folder Access"
                Spacer(modifier = Modifier.height(24.dp))
                Card(
                    shape = CardShape,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = CardShape
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Shield icon in tonal circle
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Text(
                            text = "Storage Permission Required",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Concise 3-line explanation
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "• WhatsApp caches temporary status stories in your local storage folder.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "• Grant folder access so Personal Media Saver can discover and save them.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "• Zero analytics or cloud uploads. All operations run 100% on your device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = onGrantAccess,
                            shape = CircleShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Grant Folder Access",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                // Top bar with selection count + Save button (animated visibility)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val selectionCount = selectedStatusIds.size
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = if (selectionCount > 0) "$selectionCount selected" else "${displayedStatuses.size} statuses",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (selectionCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = selectionCount > 0,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val toSave = uiState.statuses.filter { it.id in selectedStatusIds && !it.isSaved }
                                toSave.forEach { onSaveStatus(it) }
                                selectedStatusIds = emptySet()
                            },
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SaveAlt,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Save ($selectionCount)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Filters row: All, Photos, Videos + Saved filter chip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = uiState.filter == StatusFilter.ALL && !filterSavedOnly,
                        onClick = {
                            filterSavedOnly = false
                            onSetFilter(StatusFilter.ALL)
                        },
                        label = { Text("All") },
                        shape = ChipShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )

                    FilterChip(
                        selected = uiState.filter == StatusFilter.IMAGES && !filterSavedOnly,
                        onClick = {
                            filterSavedOnly = false
                            onSetFilter(StatusFilter.IMAGES)
                        },
                        label = { Text("Photos") },
                        shape = ChipShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )

                    FilterChip(
                        selected = uiState.filter == StatusFilter.VIDEOS && !filterSavedOnly,
                        onClick = {
                            filterSavedOnly = false
                            onSetFilter(StatusFilter.VIDEOS)
                        },
                        label = { Text("Videos") },
                        shape = ChipShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )

                    FilterChip(
                        selected = filterSavedOnly,
                        onClick = { filterSavedOnly = !filterSavedOnly },
                        leadingIcon = if (filterSavedOnly) {
                            {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
                        label = { Text("Saved") },
                        shape = ChipShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                }

                // 2-column grid of StatusCard
                if (displayedStatuses.isEmpty() && !uiState.isScanning) {
                    EmptyState(
                        icon = Icons.Rounded.PhotoLibrary,
                        title = "No Statuses Found",
                        description = "Open WhatsApp and view status updates first. They will appear here automatically.",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = displayedStatuses,
                            key = { it.id }
                        ) { status ->
                            val isSelected = status.id in selectedStatusIds
                            StatusCard(
                                status = status,
                                isSelected = isSelected,
                                onClick = {
                                    if (selectedStatusIds.isNotEmpty() || !status.isSaved) {
                                        // Toggle selection
                                        selectedStatusIds = if (isSelected) {
                                            selectedStatusIds - status.id
                                        } else {
                                            selectedStatusIds + status.id
                                        }
                                    } else {
                                        // Open media directly
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                                val mimeType = if (status.mediaType == StatusMediaType.VIDEO) "video/*" else "image/*"
                                                setDataAndType(Uri.parse(status.uriString), mimeType)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    }
                                }
                            )
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
}

@Preview(name = "Status Screen - Dark", showBackground = true)
@Composable
private fun StatusBrowserScreenDarkPreview() {
    MediaSaverTheme(darkTheme = true) {
        StatusBrowserScreenContent(
            uiState = StatusUiState(
                isPermissionGranted = true,
                statuses = listOf(
                    WhatsAppStatus(
                        id = "1",
                        fileName = "photo_1.jpg",
                        uriString = "content://media/1",
                        mediaType = StatusMediaType.IMAGE,
                        fileSize = 1024L * 250,
                        lastModified = System.currentTimeMillis()
                    ),
                    WhatsAppStatus(
                        id = "2",
                        fileName = "video_1.mp4",
                        uriString = "content://media/2",
                        mediaType = StatusMediaType.VIDEO,
                        fileSize = 1024L * 1024 * 3,
                        lastModified = System.currentTimeMillis(),
                        isSaved = true
                    )
                )
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onGrantAccess = {},
            onRefresh = {},
            onSaveStatus = {},
            onSetFilter = {}
        )
    }
}

@Preview(name = "Status Screen - Not Granted", showBackground = true)
@Composable
private fun StatusBrowserScreenNotGrantedPreview() {
    MediaSaverTheme(darkTheme = false) {
        StatusBrowserScreenContent(
            uiState = StatusUiState(isPermissionGranted = false),
            snackbarHostState = remember { SnackbarHostState() },
            onGrantAccess = {},
            onRefresh = {},
            onSaveStatus = {},
            onSetFilter = {}
        )
    }
}
