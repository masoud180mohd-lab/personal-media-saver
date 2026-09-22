package com.mediasaver.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Home : Screen("home", "Home", Icons.Rounded.Home)
    data object Downloads : Screen("downloads", "Downloads", Icons.Rounded.Download)
    data object History : Screen("history", "History", Icons.Rounded.History)
    data object Status : Screen("status", "Status", Icons.Rounded.PhotoLibrary)
    data object Settings : Screen("settings", "Settings", Icons.Rounded.Settings)
    data object InstagramLogin : Screen("instagram_login", "Instagram Login", Icons.Rounded.Settings)

    companion object {
        val bottomNavItems get() = listOf(Home, Downloads, History, Status)
    }
}
