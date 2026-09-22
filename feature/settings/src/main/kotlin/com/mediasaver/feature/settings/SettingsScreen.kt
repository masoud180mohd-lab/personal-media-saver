package com.mediasaver.feature.settings

import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mediasaver.core.model.AppSettings
import com.mediasaver.core.model.ThemeMode
import com.mediasaver.core.ui.components.AppSnackbar
import com.mediasaver.core.ui.components.SectionHeader
import com.mediasaver.core.ui.components.SettingsRow
import com.mediasaver.core.ui.theme.CardShape
import com.mediasaver.core.ui.theme.ChipShape
import com.mediasaver.core.ui.theme.MediaSaverTheme
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToInstagramLogin: () -> Unit = {},
    instagramSessionManager: Any? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Check Instagram login status
    val isInstagramLoggedIn = remember(instagramSessionManager) {
        try {
            val method = instagramSessionManager?.javaClass?.getMethod("isLoggedIn")
            method?.invoke(instagramSessionManager) as? Boolean ?: false
        } catch (_: Exception) {
            false
        }
    }
    var igLoggedIn by remember { mutableStateOf(isInstagramLoggedIn) }

    LaunchedEffect(Unit) {
        try {
            val method = instagramSessionManager?.javaClass?.getMethod("isLoggedIn")
            igLoggedIn = method?.invoke(instagramSessionManager) as? Boolean ?: false
        } catch (_: Exception) {}
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissMessage()
        }
    }

    SettingsScreenContent(
        modifier = modifier,
        uiState = uiState,
        igLoggedIn = igLoggedIn,
        snackbarHostState = snackbarHostState,
        onUpdateThemeMode = { viewModel.updateThemeMode(it) },
        onUpdatePreferredQuality = { viewModel.updatePreferredQuality(it) },
        onUpdateMaxConcurrentDownloads = { viewModel.updateMaxConcurrentDownloads(it) },
        onResetToDefaults = { viewModel.resetToDefaults() },
        onNavigateToInstagramLogin = onNavigateToInstagramLogin,
        onInstagramLogout = {
            try {
                val method = instagramSessionManager?.javaClass?.getMethod("clearSession")
                method?.invoke(instagramSessionManager)
                igLoggedIn = false
            } catch (_: Exception) {}
        }
    )
}

@Composable
fun SettingsScreenContent(
    modifier: Modifier = Modifier,
    uiState: SettingsUiState,
    igLoggedIn: Boolean,
    snackbarHostState: SnackbarHostState,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onUpdatePreferredQuality: (String) -> Unit,
    onUpdateMaxConcurrentDownloads: (Int) -> Unit,
    onResetToDefaults: () -> Unit,
    onNavigateToInstagramLogin: () -> Unit,
    onInstagramLogout: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var showResetDialog by remember { mutableStateOf(false) }
    var wifiOnlyEnabled by remember { mutableStateOf(uiState.settings.wifiOnlyDownload) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Screen Header
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Preferences, storage & account configuration",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 1. Download Group
            SectionHeader(title = "Download")
            Card(
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = CardShape)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Preferred Quality
                    SettingsRow(
                        icon = Icons.Rounded.HighQuality,
                        title = "Preferred Quality",
                        subtitle = "Default stream resolution when available"
                    )

                    val qualities = listOf("1080p", "720p", "480p", "360p")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        qualities.forEach { quality ->
                            val isSelected = uiState.settings.preferredQuality == quality
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onUpdatePreferredQuality(quality)
                                },
                                label = { Text(quality, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                modifier = Modifier.weight(1f),
                                shape = ChipShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Max Concurrent Downloads
                    SettingsRow(
                        icon = Icons.Rounded.Speed,
                        title = "Concurrent Downloads",
                        subtitle = "Parallel downloads: ${uiState.settings.maxConcurrentDownloads}",
                        trailingContent = {
                            Text(
                                text = "${uiState.settings.maxConcurrentDownloads}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    )

                    Slider(
                        value = uiState.settings.maxConcurrentDownloads.toFloat(),
                        onValueChange = { onUpdateMaxConcurrentDownloads(it.roundToInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Wi-Fi Only Download
                    SettingsRow(
                        icon = Icons.Rounded.Wifi,
                        title = "Wi-Fi Only",
                        subtitle = "Save mobile data transfers",
                        trailingContent = {
                            Switch(
                                checked = wifiOnlyEnabled,
                                onCheckedChange = { wifiOnlyEnabled = it }
                            )
                        }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Instagram Account Session
                    SettingsRow(
                        icon = if (igLoggedIn) Icons.Rounded.Logout else Icons.Rounded.Login,
                        title = "Instagram Account",
                        subtitle = if (igLoggedIn) "Logged in — Reels & Stories enabled" else "Login required for protected reels",
                        trailingContent = {
                            if (igLoggedIn) {
                                OutlinedButton(
                                    onClick = onInstagramLogout,
                                    shape = CircleShape
                                ) {
                                    Text("Logout", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Button(
                                    onClick = onNavigateToInstagramLogin,
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text("Login", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    )
                }
            }

            // 2. Storage Group
            SectionHeader(title = "Storage")
            Card(
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = CardShape)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsRow(
                        icon = Icons.Rounded.Folder,
                        title = "Scoped Gallery Folders",
                        subtitle = "Movies/MediaSaver/ & Pictures/MediaSaver/"
                    )

                    SettingsRow(
                        icon = Icons.Rounded.Storage,
                        title = "Storage Management",
                        subtitle = "Temp disk cache is cleaned automatically after muxing"
                    )
                }
            }

            // 3. Data & Privacy Group
            SectionHeader(title = "Data & Privacy")
            Card(
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = CardShape)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsRow(
                        icon = Icons.Rounded.Security,
                        title = "Zero Telemetry",
                        subtitle = "No crashlytics, third-party trackers, or ads"
                    )

                    SettingsRow(
                        icon = Icons.Rounded.Refresh,
                        title = "Reset Preferences",
                        subtitle = "Restore default settings",
                        onClick = { showResetDialog = true }
                    )
                }
            }

            // 4. Appearance Group
            SectionHeader(title = "Appearance")
            Card(
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant, shape = CardShape)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsRow(
                        icon = Icons.Rounded.Palette,
                        title = "Theme Mode",
                        subtitle = "Select dark, light, or follow system"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ThemePillOption(
                            label = "System",
                            icon = Icons.Rounded.Palette,
                            isSelected = uiState.settings.themeMode == ThemeMode.SYSTEM,
                            onClick = { onUpdateThemeMode(ThemeMode.SYSTEM) },
                            modifier = Modifier.weight(1f)
                        )
                        ThemePillOption(
                            label = "Light",
                            icon = Icons.Rounded.LightMode,
                            isSelected = uiState.settings.themeMode == ThemeMode.LIGHT,
                            onClick = { onUpdateThemeMode(ThemeMode.LIGHT) },
                            modifier = Modifier.weight(1f)
                        )
                        ThemePillOption(
                            label = "Dark",
                            icon = Icons.Rounded.DarkMode,
                            isSelected = uiState.settings.themeMode == ThemeMode.DARK,
                            onClick = { onUpdateThemeMode(ThemeMode.DARK) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // App Version Footer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Personal Media Saver • v${uiState.appVersion}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { snackbarData ->
            AppSnackbar(snackbarData = snackbarData)
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            shape = CardShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            title = {
                Text(
                    text = "Reset settings?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will restore default download quality, concurrency, and appearance settings.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onResetToDefaults()
                        showResetDialog = false
                    }
                ) {
                    Text("Reset", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ThemePillOption(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    FilterChip(
        selected = isSelected,
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        },
        label = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
            }
        },
        modifier = modifier,
        shape = ChipShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Preview(name = "Settings Screen - Dark", showBackground = true)
@Composable
private fun SettingsScreenDarkPreview() {
    MediaSaverTheme(darkTheme = true) {
        SettingsScreenContent(
            uiState = SettingsUiState(
                settings = AppSettings(
                    preferredQuality = "1080p",
                    maxConcurrentDownloads = 3,
                    themeMode = ThemeMode.DARK
                ),
                appVersion = "1.0.0"
            ),
            igLoggedIn = true,
            snackbarHostState = remember { SnackbarHostState() },
            onUpdateThemeMode = {},
            onUpdatePreferredQuality = {},
            onUpdateMaxConcurrentDownloads = {},
            onResetToDefaults = {},
            onNavigateToInstagramLogin = {},
            onInstagramLogout = {}
        )
    }
}

@Preview(name = "Settings Screen - Light", showBackground = true)
@Composable
private fun SettingsScreenLightPreview() {
    MediaSaverTheme(darkTheme = false) {
        SettingsScreenContent(
            uiState = SettingsUiState(
                settings = AppSettings(
                    preferredQuality = "720p",
                    maxConcurrentDownloads = 2,
                    themeMode = ThemeMode.LIGHT
                ),
                appVersion = "1.0.0"
            ),
            igLoggedIn = false,
            snackbarHostState = remember { SnackbarHostState() },
            onUpdateThemeMode = {},
            onUpdatePreferredQuality = {},
            onUpdateMaxConcurrentDownloads = {},
            onResetToDefaults = {},
            onNavigateToInstagramLogin = {},
            onInstagramLogout = {}
        )
    }
}
