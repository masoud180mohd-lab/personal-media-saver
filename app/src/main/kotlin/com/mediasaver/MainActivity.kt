package com.mediasaver

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.mediasaver.core.domain.usecase.GetActiveDownloadsUseCase
import com.mediasaver.core.domain.usecase.GetSettingsUseCase
import com.mediasaver.core.model.ThemeMode
import com.mediasaver.feature.history.player.InAppPlaybackController
import com.mediasaver.ui.MediaSaverApp
import com.mediasaver.core.ui.theme.MediaSaverTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import javax.inject.Inject

/**
 * Single activity entry point hosting Compose navigation and handling incoming Share Sheet intents.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var getSettingsUseCase: GetSettingsUseCase

    @Inject
    lateinit var getActiveDownloadsUseCase: GetActiveDownloadsUseCase

    @Inject
    lateinit var playbackController: InAppPlaybackController

    private val sharedUrlState = mutableStateOf<String?>(null)
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        sharedUrlState.value = extractSharedUrl(intent)

        setContent {
            val settings by getSettingsUseCase().collectAsState(initial = null)
            val isDark = when (settings?.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> true // Dark-first default for clean utility aesthetic
            }
            val languageCode = settings?.languageCode.orEmpty()
            val baseContext = LocalContext.current
            val localizedContext = remember(baseContext, languageCode) {
                if (languageCode == "en" || languageCode == "sw") {
                    val locale = Locale.forLanguageTag(languageCode)
                    val config = Configuration(baseContext.resources.configuration).apply {
                        setLocale(locale)
                    }
                    val localeContext = baseContext.createConfigurationContext(config)
                    LocalizedActivityContext(baseContext, localeContext.resources)
                } else {
                    baseContext
                }
            }
            val localizedConfig = remember(localizedContext, languageCode) {
                localizedContext.resources.configuration
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfig
            ) {
                MediaSaverTheme(
                    darkTheme = isDark,
                    dynamicColor = false
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        MediaSaverApp(
                            sharedUrl = sharedUrlState.value,
                            onSharedUrlConsumed = { sharedUrlState.value = null },
                            getActiveDownloadsUseCase = getActiveDownloadsUseCase,
                            playbackController = playbackController
                        )
                    }
                }
            }
        }
    }

    private class LocalizedActivityContext(
        base: Context,
        private val localizedResources: Resources
    ) : ContextWrapper(base) {
        override fun getResources(): Resources = localizedResources
    }

    override fun onStop() {
        super.onStop()
        if (::playbackController.isInitialized && playbackController.state.value.isVideo) {
            playbackController.pause()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newSharedUrl = extractSharedUrl(intent)
        if (!newSharedUrl.isNullOrBlank()) {
            sharedUrlState.value = newSharedUrl
        }
    }

    private fun extractSharedUrl(intent: Intent?): String? {
        return intent?.getStringExtra("SHARED_URL")
    }
}

