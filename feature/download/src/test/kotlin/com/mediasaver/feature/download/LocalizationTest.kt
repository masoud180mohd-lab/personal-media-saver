package com.mediasaver.feature.download

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.ui.R as UiR
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class LocalizationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun resourcesForLocale(language: String): Pair<Context, Resources> {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val localeRes = base.createConfigurationContext(config).resources
        val wrapped = object : ContextWrapper(base) {
            override fun getResources(): Resources = localeRes
        }
        return wrapped to localeRes
    }

    @Test
    fun `English and Swahili string resources resolve expected translations`() {
        val (_, enRes) = resourcesForLocale("en")
        val (_, swRes) = resourcesForLocale("sw")

        assertEquals("Home", enRes.getString(UiR.string.nav_home))
        assertEquals("Mwanzo", swRes.getString(UiR.string.nav_home))

        assertEquals("Pause All", enRes.getString(UiR.string.downloads_pause_all))
        assertEquals("Sitisha zote", swRes.getString(UiR.string.downloads_pause_all))

        assertEquals("Resume All", enRes.getString(UiR.string.downloads_resume_all))
        assertEquals("Endeleza zote", swRes.getString(UiR.string.downloads_resume_all))

        assertEquals("Playlist folder", enRes.getString(UiR.string.playlist_folder))
        assertEquals("Folda ya playlist", swRes.getString(UiR.string.playlist_folder))

        assertEquals("History", enRes.getString(UiR.string.history_title))
        assertEquals("Historia", swRes.getString(UiR.string.history_title))

        assertEquals("Kiswahili", swRes.getString(UiR.string.settings_language_swahili))
        assertEquals("Lugha ya kifaa", swRes.getString(UiR.string.settings_language_system))
    }

    @Test
    fun `DownloadScreenContent renders Swahili localization when locale provider switches to sw`() {
        val (swContext, swRes) = resourcesForLocale("sw")
        val task = DownloadTask(
            id = "t1",
            url = "https://youtube.com/watch?v=1",
            platform = PlatformId.YOUTUBE,
            mediaId = "1",
            title = "001 - Darsa",
            status = DownloadStatus.DOWNLOADING
        )

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalContext provides swContext,
                LocalConfiguration provides swRes.configuration
            ) {
                DownloadScreenContent(
                    uiState = DownloadUiState(activeTasks = listOf(task)),
                    snackbarHostState = SnackbarHostState(),
                    onCancelDownload = {},
                    onRetryDownload = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Upakuaji unaoendelea").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sitisha zote").assertIsDisplayed()
        composeTestRule.onNodeWithText("Inapakuliwa").assertIsDisplayed()
    }
}
