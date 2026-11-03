package com.mediasaver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.mediasaver.core.domain.usecase.GetActiveDownloadsUseCase
import com.mediasaver.core.domain.usecase.GetSettingsUseCase
import com.mediasaver.core.model.ThemeMode
import com.mediasaver.ui.MediaSaverApp
import com.mediasaver.core.ui.theme.MediaSaverTheme
import dagger.hilt.android.AndroidEntryPoint
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

    private val sharedUrlState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sharedUrlState.value = extractSharedUrl(intent)

        setContent {
            val settings by getSettingsUseCase().collectAsState(initial = null)
            val isDark = when (settings?.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                else -> true // Dark-first default for clean utility aesthetic
            }

            MediaSaverTheme(
                darkTheme = isDark,
                dynamicColor = false
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    MediaSaverApp(
                        sharedUrl = sharedUrlState.value,
                        getActiveDownloadsUseCase = getActiveDownloadsUseCase
                    )
                }
            }
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

