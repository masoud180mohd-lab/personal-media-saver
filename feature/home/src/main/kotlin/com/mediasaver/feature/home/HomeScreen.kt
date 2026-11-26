package com.mediasaver.feature.home

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Search
import com.mediasaver.core.common.FileNameSanitizer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.mediasaver.core.model.PlaylistItem
import com.mediasaver.core.model.isCarousel
import androidx.compose.ui.text.style.TextOverflow
import com.mediasaver.core.ui.theme.CardShape
import com.mediasaver.core.ui.theme.ChipShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.ui.R as UiR
import com.mediasaver.core.ui.components.AppSnackbar
import com.mediasaver.core.ui.components.ErrorState
import com.mediasaver.core.ui.components.MediaPreviewCard
import com.mediasaver.core.ui.components.QualityChipRow
import com.mediasaver.core.ui.components.UrlInputCard
import com.mediasaver.core.ui.theme.MediaSaverTheme
import com.mediasaver.core.ui.util.rememberIsReduceMotionEnabled

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    initialSharedUrl: String? = null,
    onSharedUrlConsumed: () -> Unit = {},
    onNavigateToDownloads: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) {
            viewModel.onUrlChange(initialSharedUrl)
            viewModel.processUrl(initialSharedUrl)
            onSharedUrlConsumed()
        }
    }

    LaunchedEffect(uiState.downloadEnqueuedMessage) {
        uiState.downloadEnqueuedMessage?.let {
            viewModel.clearEnqueuedMessage()
            onNavigateToDownloads()
        }
    }

    HomeScreenContent(
        modifier = modifier,
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onUrlChange = { viewModel.onUrlChange(it) },
        onProcessUrl = {
            keyboardController?.hide()
            viewModel.processUrl()
        },
        onPaste = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString().orEmpty()
                if (text.isNotBlank()) {
                    viewModel.onUrlChange(text)
                }
            }
        },
        onSelectStream = { viewModel.onSelectStream(it) },
        onStartDownload = { stream ->
            viewModel.startDownload(stream)
        },
        onStartBatchDownload = { streams ->
            viewModel.startBatchDownload(streams)
        },
        onTogglePlaylistVideo = { videoId ->
            viewModel.onTogglePlaylistVideo(videoId)
        },
        onSelectAllPlaylistVideos = { selectAll ->
            viewModel.onSelectAllPlaylistVideos(selectAll)
        },
        onSelectPlaylistQuality = { quality ->
            viewModel.onSelectPlaylistQuality(quality)
        },
        onToggleOrganizeInPlaylistFolder = { enabled ->
            viewModel.onToggleOrganizeInPlaylistFolder(enabled)
        },
        onToggleNumberPlaylistEpisodes = { enabled ->
            viewModel.onToggleNumberPlaylistEpisodes(enabled)
        },
        onStartPlaylistBatchDownload = {
            viewModel.startPlaylistBatchDownload()
        }
    )
}

@Composable
fun HomeScreenContent(
    modifier: Modifier = Modifier,
    uiState: HomeUiState,
    snackbarHostState: SnackbarHostState,
    onUrlChange: (String) -> Unit,
    onProcessUrl: () -> Unit,
    onPaste: () -> Unit,
    onSelectStream: (StreamInfo) -> Unit,
    onStartDownload: (StreamInfo?) -> Unit,
    onStartBatchDownload: (List<StreamInfo>) -> Unit,
    onTogglePlaylistVideo: (String) -> Unit = {},
    onSelectAllPlaylistVideos: (Boolean) -> Unit = {},
    onSelectPlaylistQuality: (String) -> Unit = {},
    onToggleOrganizeInPlaylistFolder: (Boolean) -> Unit = {},
    onToggleNumberPlaylistEpisodes: (Boolean) -> Unit = {},
    onStartPlaylistBatchDownload: () -> Unit = {}
) {
    val isReduceMotion = rememberIsReduceMotionEnabled()
    val haptic = LocalHapticFeedback.current
    
    val isCarousel = uiState.availableStreams.isCarousel()
    var activeCarouselSlide by remember(uiState.availableStreams) { androidx.compose.runtime.mutableStateOf(uiState.availableStreams.firstOrNull()) }
    
    LaunchedEffect(uiState.availableStreams) {
        if (isCarousel && uiState.availableStreams.isNotEmpty()) {
            activeCarouselSlide = uiState.availableStreams.first()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Screen Title & Subtitle: "Clean Utility"
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(UiR.string.home_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(UiR.string.home_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // UrlInputCard
            UrlInputCard(
                url = uiState.urlInput,
                onUrlChange = onUrlChange,
                detectedPlatform = uiState.detectedPlatform,
                isLoading = uiState.isExtracting,
                onPasteClick = onPaste,
                onFetchClick = onProcessUrl,
                onClearClick = { onUrlChange("") }
            )

            // Inline ErrorState (NOT full-width red banner)
            AnimatedVisibility(
                visible = uiState.errorMessage != null,
                enter = if (isReduceMotion) fadeIn() else fadeIn() + expandVertically(),
                exit = if (isReduceMotion) fadeOut() else fadeOut() + shrinkVertically()
            ) {
                uiState.errorMessage?.let { errorMsg ->
                    ErrorState(
                        message = errorMsg,
                        onRetry = { onProcessUrl() }
                    )
                }
            }

            // Media Preview Card + Quality Chips + Pill Download Button
            AnimatedVisibility(
                visible = uiState.mediaInfo != null,
                enter = if (isReduceMotion) fadeIn() else fadeIn() + expandVertically(),
                exit = if (isReduceMotion) fadeOut() else fadeOut() + shrinkVertically()
            ) {
                uiState.mediaInfo?.let { media ->
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        MediaPreviewCard(
                            mediaInfo = media,
                            availableStreams = uiState.availableStreams,
                            onActiveSlideChanged = { stream ->
                                if (isCarousel) {
                                    activeCarouselSlide = stream
                                }
                            }
                        )

                        if (media.isPlaylist) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onStartPlaylistBatchDownload()
                                },
                                enabled = !uiState.isEnqueuing && uiState.selectedPlaylistVideoIds.isNotEmpty(),
                                shape = CircleShape,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                )
                            ) {
                                if (uiState.isEnqueuing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(UiR.string.playlist_queuing, uiState.playlistEnqueueProgress ?: "..."),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(UiR.string.playlist_download_selected, uiState.selectedPlaylistVideoIds.size),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            val playlistPlatformFolder = when (media.platform) {
                                PlatformId.YOUTUBE -> "YouTube"
                                PlatformId.TIKTOK -> "TikTok"
                                PlatformId.WHATSAPP -> "WhatsApp"
                                PlatformId.INSTAGRAM -> "Instagram"
                                PlatformId.FACEBOOK -> "Facebook"
                                PlatformId.UNKNOWN -> "Downloads"
                            }
                            PlaylistSelectionCard(
                                playlistItems = media.playlistItems,
                                totalPlaylistCount = media.totalPlaylistCount,
                                playlistTitle = media.title,
                                platformFolderName = playlistPlatformFolder,
                                selectedVideoIds = uiState.selectedPlaylistVideoIds,
                                selectedQuality = uiState.playlistQuality,
                                organizeInPlaylistFolder = uiState.organizeInPlaylistFolder,
                                numberPlaylistEpisodes = uiState.numberPlaylistEpisodes,
                                onToggleVideo = onTogglePlaylistVideo,
                                onSelectAll = onSelectAllPlaylistVideos,
                                onSelectQuality = onSelectPlaylistQuality,
                                onToggleOrganizeInPlaylistFolder = onToggleOrganizeInPlaylistFolder,
                                onToggleNumberPlaylistEpisodes = onToggleNumberPlaylistEpisodes
                            )
                        } else {
                            if (uiState.availableStreams.isNotEmpty() && !isCarousel) {
                                QualityChipRow(
                                    availableStreams = uiState.availableStreams,
                                    selectedStream = uiState.selectedStream,
                                    onSelectStream = onSelectStream
                                )
                            }

                            if (isCarousel) {
                                // Two buttons for carousel: Download Current Slide & Download All
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            activeCarouselSlide?.let { onStartDownload(it) }
                                        },
                                        enabled = !uiState.isEnqueuing,
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.onSurface,
                                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(UiR.string.home_save_slide),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onStartBatchDownload(uiState.availableStreams)
                                        },
                                        enabled = !uiState.isEnqueuing,
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(52.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                        )
                                    ) {
                                        if (uiState.isEnqueuing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            Text(
                                                text = stringResource(UiR.string.home_save_all),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            } else {
                                // Large filled Download button (full width, pill)
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onStartDownload(null)
                                    },
                                    enabled = !uiState.isEnqueuing && uiState.selectedStream != null && uiState.availableStreams.isNotEmpty(),
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                    )
                                ) {
                                    if (uiState.isEnqueuing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(UiR.string.home_queuing_download),
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(UiR.string.home_download),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { snackbarData ->
            AppSnackbar(snackbarData = snackbarData)
        }
    }
}

private const val PLAYLIST_PAGE_SIZE = 50

@Composable
private fun PlaylistSelectionCard(
    playlistItems: List<PlaylistItem>,
    totalPlaylistCount: Int?,
    playlistTitle: String?,
    platformFolderName: String,
    selectedVideoIds: Set<String>,
    selectedQuality: String,
    organizeInPlaylistFolder: Boolean,
    numberPlaylistEpisodes: Boolean,
    onToggleVideo: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onSelectQuality: (String) -> Unit,
    onToggleOrganizeInPlaylistFolder: (Boolean) -> Unit,
    onToggleNumberPlaylistEpisodes: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val allSelected = playlistItems.isNotEmpty() && selectedVideoIds.size == playlistItems.size
    val qualityOptions = listOf("1080p", "720p", "480p", "360p", "Audio")
    val folderPreview = remember(playlistTitle) {
        FileNameSanitizer.sanitize(playlistTitle, fallback = "")
            .take(60)
            .trimEnd('.', '_', '-', ' ')
            .ifBlank { null }
    }
    var filterQuery by remember(playlistItems) { mutableStateOf("") }
    var visibleCount by remember(playlistItems, filterQuery) { mutableIntStateOf(PLAYLIST_PAGE_SIZE) }

    val indexedItems = remember(playlistItems) {
        playlistItems.mapIndexed { index, item -> (index + 1) to item }
    }
    val trimmedQuery = filterQuery.trim()
    val filteredItems = remember(indexedItems, trimmedQuery) {
        if (trimmedQuery.isBlank()) {
            indexedItems
        } else {
            indexedItems.filter { (number, item) ->
                number.toString() == trimmedQuery ||
                    item.title.contains(trimmedQuery, ignoreCase = true) ||
                    (item.author?.contains(trimmedQuery, ignoreCase = true) == true)
            }
        }
    }
    val visibleItems = remember(filteredItems, visibleCount) {
        filteredItems.take(visibleCount)
    }

    Card(
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier
            .fillMaxWidth()
            .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = CardShape)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(UiR.string.playlist_batch_quality),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                qualityOptions.forEach { quality ->
                    val isSelected = selectedQuality.equals(quality, ignoreCase = true)
                    val displayQuality = if (quality.equals("Audio", ignoreCase = true)) {
                        stringResource(UiR.string.playlist_audio)
                    } else {
                        quality
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectQuality(quality) },
                        label = { Text(displayQuality, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        shape = ChipShape,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = organizeInPlaylistFolder,
                    onClick = { onToggleOrganizeInPlaylistFolder(!organizeInPlaylistFolder) },
                    label = { Text(stringResource(UiR.string.playlist_folder)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    shape = ChipShape,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
                FilterChip(
                    selected = numberPlaylistEpisodes,
                    onClick = { onToggleNumberPlaylistEpisodes(!numberPlaylistEpisodes) },
                    label = { Text(stringResource(UiR.string.playlist_number_episodes)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.FormatListNumbered,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    shape = ChipShape,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }

            if (organizeInPlaylistFolder && folderPreview != null) {
                Text(
                    text = stringResource(UiR.string.playlist_save_path, platformFolderName, folderPreview),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(UiR.string.playlist_selected_count, selectedVideoIds.size, playlistItems.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (totalPlaylistCount != null && totalPlaylistCount > playlistItems.size) {
                        Text(
                            text = stringResource(UiR.string.playlist_showing_count, playlistItems.size, totalPlaylistCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                TextButton(onClick = { onSelectAll(!allSelected) }) {
                    Text(stringResource(if (allSelected) UiR.string.playlist_clear_all else UiR.string.playlist_select_all))
                }
            }

            if (playlistItems.size > 10) {
                OutlinedTextField(
                    value = filterQuery,
                    onValueChange = { filterQuery = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(UiR.string.playlist_filter_hint)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        if (filterQuery.isNotEmpty()) {
                            IconButton(onClick = { filterQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(UiR.string.playlist_clear_filter)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (filteredItems.isEmpty() && filterQuery.isNotBlank()) {
                Text(
                    text = stringResource(UiR.string.playlist_no_matches, filterQuery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    visibleItems.forEach { (number, item) ->
                        val checked = item.videoId in selectedVideoIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleVideo(item.videoId) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggleVideo(item.videoId) }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "$number. ${item.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val meta = listOfNotNull(
                                    item.author?.takeIf { it.isNotBlank() },
                                    item.durationSeconds?.takeIf { it > 0 }?.let { formatPlaylistDuration(it) }
                                ).joinToString(" • ")
                                if (meta.isNotBlank()) {
                                    Text(
                                        text = meta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (filteredItems.size > visibleCount) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(UiR.string.playlist_showing_count, visibleItems.size, filteredItems.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val remaining = filteredItems.size - visibleCount
                    val nextBatch = minOf(PLAYLIST_PAGE_SIZE, remaining)
                    TextButton(onClick = { visibleCount += PLAYLIST_PAGE_SIZE }) {
                        Text(stringResource(UiR.string.playlist_show_more, nextBatch))
                    }
                    TextButton(onClick = { visibleCount = filteredItems.size }) {
                        Text(stringResource(UiR.string.playlist_show_all, filteredItems.size))
                    }
                }
            }
        }
    }
}

private fun formatPlaylistDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format("%d:%02d", minutes, secs)
    }
}

@Preview(name = "Home Screen - Dark", showBackground = true)
@Composable
private fun HomeScreenDarkPreview() {
    MediaSaverTheme(darkTheme = true) {
        HomeScreenContent(
            uiState = HomeUiState(
                urlInput = "https://youtube.com/watch?v=demo",
                detectedPlatform = PlatformId.YOUTUBE,
                mediaInfo = MediaInfo(
                    id = "demo",
                    platform = PlatformId.YOUTUBE,
                    title = "Clean Utility Design in Modern Android Apps",
                    thumbnailUrl = null,
                    durationSeconds = 620,
                    sourceUrl = "https://youtube.com/watch?v=demo"
                ),
                availableStreams = listOf(
                    StreamInfo(url = "s1", quality = "1080p", format = "mp4", fileSize = 45_000_000L),
                    StreamInfo(url = "s2", quality = "720p", format = "mp4", fileSize = 22_000_000L),
                    StreamInfo(url = "s3", quality = "Audio", format = "m4a", isAudioOnly = true, fileSize = 4_000_000L)
                )
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onUrlChange = {},
            onProcessUrl = {},
            onPaste = {},
            onSelectStream = {},
            onStartDownload = {},
            onStartBatchDownload = {}
        )
    }
}

@Preview(name = "Home Screen - Light", showBackground = true)
@Composable
private fun HomeScreenLightPreview() {
    MediaSaverTheme(darkTheme = false) {
        HomeScreenContent(
            uiState = HomeUiState(
                urlInput = "",
                detectedPlatform = PlatformId.UNKNOWN
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onUrlChange = {},
            onProcessUrl = {},
            onPaste = {},
            onSelectStream = {},
            onStartDownload = {},
            onStartBatchDownload = {}
        )
    }
}
