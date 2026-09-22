package com.mediasaver.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mediasaver.core.domain.usecase.GetActiveDownloadsUseCase
import com.mediasaver.feature.download.DownloadScreen
import com.mediasaver.feature.history.HistoryScreen
import com.mediasaver.feature.home.HomeScreen
import com.mediasaver.feature.settings.SettingsScreen
import com.mediasaver.feature.status.StatusBrowserScreen
import com.mediasaver.platform.instagram.InstagramSessionManager
import com.mediasaver.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSaverApp(
    sharedUrl: String?,
    getActiveDownloadsUseCase: GetActiveDownloadsUseCase,
    instagramSessionManager: InstagramSessionManager,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

    val activeDownloads by getActiveDownloadsUseCase().collectAsState(initial = emptyList())
    val activeCount = activeDownloads.size

    val isSettingsOpen = currentRoute == Screen.Settings.route
    val isInstagramLogin = currentRoute == Screen.InstagramLogin.route

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
                            isInstagramLogin -> "Instagram Login"
                            isSettingsOpen -> "Settings"
                            else -> "Personal Media Saver"
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    if (isSettingsOpen || isInstagramLogin) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    if (!isSettingsOpen && !isInstagramLogin) {
                        IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = "Settings",
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
            AnimatedVisibility(visible = !isSettingsOpen && !isInstagramLogin) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Screen.bottomNavItems.forEach { screen ->
                        val selected = currentRoute == screen.route

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
                                        Icon(screen.icon, contentDescription = screen.title)
                                    }
                                } else {
                                    Icon(screen.icon, contentDescription = screen.title)
                                }
                            },
                            label = { Text(screen.title) }
                        )
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
                    onNavigateToDownloads = {
                        navController.navigate(Screen.Downloads.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    }
                )
            }

            composable(Screen.Downloads.route) {
                DownloadScreen()
            }

            composable(Screen.History.route) {
                HistoryScreen()
            }

            composable(Screen.Status.route) {
                StatusBrowserScreen()
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToInstagramLogin = {
                        navController.navigate(Screen.InstagramLogin.route)
                    },
                    instagramSessionManager = instagramSessionManager
                )
            }

            composable(Screen.InstagramLogin.route) {
                InstagramLoginScreen(
                    sessionManager = instagramSessionManager,
                    onLoginSuccess = {
                        navController.popBackStack()
                    },
                    onLoginCancelled = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
