package com.mediasaver.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.mediasaver.core.model.PlaybackState
import com.mediasaver.core.ui.util.rememberIsReduceMotionEnabled
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediasaver.core.ui.R as UiR
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mediasaver.core.domain.usecase.GetActiveDownloadsUseCase
import com.mediasaver.feature.download.DownloadScreen
import com.mediasaver.feature.history.HistoryScreen
import com.mediasaver.feature.history.openMediaExternally
import com.mediasaver.feature.history.player.FullAudioPlayerSheet
import com.mediasaver.feature.history.player.InAppPlaybackController
import com.mediasaver.feature.history.player.InAppVideoPlayerDialog
import com.mediasaver.feature.history.player.MiniAudioPlayerBar
import com.mediasaver.feature.home.HomeScreen
import com.mediasaver.feature.settings.SettingsScreen
import com.mediasaver.feature.status.StatusBrowserScreen
import com.mediasaver.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSaverApp(
    sharedUrl: String?,
    getActiveDownloadsUseCase: GetActiveDownloadsUseCase,
    playbackController: InAppPlaybackController? = null,
    onSharedUrlConsumed: () -> Unit = {},
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

    val activeDownloads by getActiveDownloadsUseCase().collectAsState(initial = emptyList())
    val activeCount = activeDownloads.size

    val playbackState by playbackController?.state?.collectAsState()
        ?: remember { mutableStateOf(PlaybackState()) }
    val videoAspectRatio by playbackController?.videoAspectRatio?.collectAsState()
        ?: remember { mutableFloatStateOf(16f / 9f) }
    var showFullAudioPlayer by remember { mutableStateOf(false) }

    val isSettingsOpen = currentRoute == Screen.Settings.route
    val context = LocalContext.current

    LaunchedEffect(playbackState.hasActiveItem, playbackState.isVideo) {
        if (!playbackState.hasActiveItem || playbackState.isVideo) {
            showFullAudioPlayer = false
        }
    }

    androidx.compose.runtime.LaunchedEffect(sharedUrl) {
        if (!sharedUrl.isNullOrBlank() && currentRoute != Screen.Home.route) {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
            }
        }
    }

    val isReduceMotion = rememberIsReduceMotionEnabled()
    val density = LocalDensity.current
    val slideOffsetPx = remember(density) { density.run { 12.dp.roundToPx() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            isSettingsOpen -> stringResource(UiR.string.nav_settings)
                            else -> stringResource(UiR.string.nav_app_name)
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    if (isSettingsOpen) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(UiR.string.common_back)
                            )
                        }
                    }
                },
                actions = {
                    if (!isSettingsOpen) {
                        IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = stringResource(UiR.string.nav_settings),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Column {
                if (playbackState.hasActiveItem && !playbackState.isVideo && !isSettingsOpen) {
                    MiniAudioPlayerBar(
                        state = playbackState,
                        onExpand = { showFullAudioPlayer = true },
                        onTogglePlayPause = { playbackController?.togglePlayPause() },
                        onClose = { playbackController?.stop() }
                    )
                }
                AnimatedVisibility(visible = !isSettingsOpen) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Screen.bottomNavItems.forEach { screen ->
                            val selected = currentRoute == screen.route
                            val screenLabel = when (screen) {
                                Screen.Home -> stringResource(UiR.string.nav_home)
                                Screen.Downloads -> stringResource(UiR.string.nav_downloads)
                                Screen.History -> stringResource(UiR.string.nav_history)
                                Screen.Status -> stringResource(UiR.string.nav_status)
                                Screen.Settings -> stringResource(UiR.string.nav_settings)
                            }

                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (currentRoute != screen.route) {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    if (screen == Screen.Downloads && activeCount > 0) {
                                        BadgedBox(
                                            badge = {
                                                Badge {
                                                    Text("$activeCount")
                                                }
                                            }
                                        ) {
                                            Icon(screen.icon, contentDescription = screenLabel)
                                        }
                                    } else {
                                        Icon(screen.icon, contentDescription = screenLabel)
                                    }
                                },
                                label = { Text(screenLabel) }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            enterTransition = {
                if (isReduceMotion) fadeIn(tween(0)) else fadeIn(tween(250)) + slideInHorizontally(tween(250)) { slideOffsetPx }
            },
            exitTransition = {
                if (isReduceMotion) fadeOut(tween(0)) else fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -slideOffsetPx }
            },
            popEnterTransition = {
                if (isReduceMotion) fadeIn(tween(0)) else fadeIn(tween(250)) + slideInHorizontally(tween(250)) { -slideOffsetPx }
            },
            popExitTransition = {
                if (isReduceMotion) fadeOut(tween(0)) else fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { slideOffsetPx }
            }
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    initialSharedUrl = sharedUrl,
                    onSharedUrlConsumed = onSharedUrlConsumed,
                    onNavigateToDownloads = {
                        navController.navigate(Screen.Downloads.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(Screen.Downloads.route) {
                DownloadScreen()
            }

            composable(Screen.History.route) {
                HistoryScreen(
                    onPlayInApp = playbackController?.let { controller ->
                        { task, queue, isVideo ->
                            controller.play(task, queue, isVideo)
                            if (!isVideo) {
                                showFullAudioPlayer = true
                            }
                        }
                    }
                )
            }

            composable(Screen.Status.route) {
                StatusBrowserScreen()
            }

            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }

    if (showFullAudioPlayer && playbackState.hasActiveItem && !playbackState.isVideo) {
        FullAudioPlayerSheet(
            state = playbackState,
            onDismiss = { showFullAudioPlayer = false },
            onTogglePlayPause = { playbackController?.togglePlayPause() },
            onSeekTo = { playbackController?.seekTo(it) },
            onRewind10 = { playbackController?.seekBy(-10_000L) },
            onForward10 = { playbackController?.seekBy(10_000L) },
            onPrevious = { playbackController?.playPrevious() },
            onNext = { playbackController?.playNext() },
            onSelectSpeed = { playbackController?.setSpeed(it) },
            onOpenExternal = {
                playbackState.currentItem?.let { item ->
                    openMediaExternally(context, item)
                }
            }
        )
    }

    if (playbackState.hasActiveItem && playbackState.isVideo) {
        InAppVideoPlayerDialog(
            state = playbackState,
            aspectRatio = videoAspectRatio,
            onAttachSurface = { holder -> playbackController?.attachVideoSurface(holder) },
            onTogglePlayPause = { playbackController?.togglePlayPause() },
            onSeekTo = { playbackController?.seekTo(it) },
            onRewind10 = { playbackController?.seekBy(-10_000L) },
            onForward10 = { playbackController?.seekBy(10_000L) },
            onSelectSpeed = { playbackController?.setSpeed(it) },
            onOpenExternal = {
                playbackState.currentItem?.let { item ->
                    playbackController?.pause()
                    openMediaExternally(context, item)
                }
            },
            onClose = { playbackController?.stop() }
        )
    }
}
