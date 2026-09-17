package com.mediasaver.core.model

/**
 * Domain representation of user-configured application preferences.
 */
data class AppSettings(
    val preferredQuality: String = "720p",
    val downloadDirectoryUri: String? = null,
    val maxConcurrentDownloads: Int = 3,
    val wifiOnlyDownload: Boolean = false,
    val maxDownloadSizeMb: Int = 2048,
    val whatsappStatusUri: String? = null,
    val whatsappBusinessStatusUri: String? = null,
    val saveDownloadHistory: Boolean = true,
    val autoDeleteTempFiles: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)
