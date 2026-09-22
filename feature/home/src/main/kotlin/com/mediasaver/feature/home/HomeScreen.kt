package com.mediasaver.feature.home

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
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
    onNavigateToDownloads: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
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
        }
    }

    LaunchedEffect(uiState.downloadEnqueuedMessage) {
        uiState.downloadEnqueuedMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearEnqueuedMessage()
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
            onNavigateToDownloads()
        },
        onStartBatchDownload = { streams ->
            viewModel.startBatchDownload(streams)
            onNavigateToDownloads()
        },
        onNavigateToSettings = onNavigateToSettings
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
    onNavigateToSettings: () -> Unit = {}
) {
    val isReduceMotion = rememberIsReduceMotionEnabled()
    val haptic = LocalHapticFeedback.current
    
    val isCarousel = uiState.availableStreams.size > 1 && uiState.availableStreams.all { it.quality?.startsWith("Slide ") == true }
    var activeCarouselSlide by remember { androidx.compose.runtime.mutableStateOf<StreamInfo?>(null) }
    
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
                    text = "Save Media",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Download videos and audio with no ads or tracking",
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
                    val isAuthOrSettings = errorMsg.contains("Settings", ignoreCase = true) ||
                        errorMsg.contains("Login", ignoreCase = true)

                    ErrorState(
                        message = errorMsg,
                        onRetry = { onProcessUrl() },
                        actionLabel = if (isAuthOrSettings) "Open Settings / Login" else null,
                        onActionClick = if (isAuthOrSettings) onNavigateToSettings else null
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
                                        text = "Save Slide",
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
                                            text = "Save All",
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
                                enabled = !uiState.isEnqueuing,
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
                                        text = "Queuing Download...",
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
                                        text = "Download",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
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
