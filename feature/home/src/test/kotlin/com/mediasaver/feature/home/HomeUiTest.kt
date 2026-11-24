package com.mediasaver.feature.home

import com.mediasaver.core.common.PlatformDetector
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.PlaylistItem
import com.mediasaver.core.model.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class HomeUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `default HomeUiState has empty input and unknown platform`() {
        val state = HomeUiState()
        assertEquals("", state.urlInput)
        assertEquals(PlatformId.UNKNOWN, state.detectedPlatform)
        assertFalse(state.isExtracting)
        assertNull(state.mediaInfo)
        assertTrue(state.availableStreams.isEmpty())
        assertNull(state.selectedStream)
    }

    @Test
    fun `platform detector correctly identifies supported domains for home input`() {
        assertEquals(PlatformId.YOUTUBE, PlatformDetector.detect("https://www.youtube.com/watch?v=123"))
        assertEquals(PlatformId.TIKTOK, PlatformDetector.detect("https://www.tiktok.com/@user/video/123"))
        assertEquals(PlatformId.INSTAGRAM, PlatformDetector.detect("https://www.instagram.com/p/123/"))
        assertEquals(PlatformId.WHATSAPP, PlatformDetector.detect("whatsapp://status"))
        assertEquals(PlatformId.UNKNOWN, PlatformDetector.detect("https://example.com/video"))
    }

    @Test
    fun `HomeUiState copy with media updates streams and selected stream`() {
        val stream1 = StreamInfo(url = "https://stream1", quality = "720p", format = "mp4")
        val stream2 = StreamInfo(url = "https://stream2", quality = "1080p", format = "mp4")
        val media = MediaInfo(
            id = "test_id",
            platform = PlatformId.YOUTUBE,
            title = "Test Video",
            sourceUrl = "https://youtube.com/watch?v=test"
        )

        val state = HomeUiState(
            urlInput = "https://youtube.com/watch?v=test",
            detectedPlatform = PlatformId.YOUTUBE,
            mediaInfo = media,
            availableStreams = listOf(stream1, stream2),
            selectedStream = stream1
        )

        assertEquals("Test Video", state.mediaInfo?.title)
        assertEquals(2, state.availableStreams.size)
        assertEquals("720p", state.selectedStream?.quality)
    }

    @Test
    fun `HomeScreenContent displays empty state correctly`() {
        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(),
                snackbarHostState = SnackbarHostState(),
                onUrlChange = {},
                onProcessUrl = {},
                onPaste = {},
                onSelectStream = {},
                onStartDownload = {},
                onStartBatchDownload = {}
            )
        }

        composeTestRule.onNodeWithText("Save Media").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fetch").assertIsNotEnabled()
    }

    @Test
    fun `HomeScreenContent enables fetch button when url is entered`() {
        val enteredUrl = androidx.compose.runtime.mutableStateOf("")
        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(urlInput = enteredUrl.value),
                snackbarHostState = SnackbarHostState(),
                onUrlChange = { enteredUrl.value = it },
                onProcessUrl = {},
                onPaste = {},
                onSelectStream = {},
                onStartDownload = {},
                onStartBatchDownload = {}
            )
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("https://www.youtube.com/watch?v=fixture")
        composeTestRule.onNodeWithText("Fetch").assertIsEnabled()
    }
    
    @Test
    fun `HomeScreenContent displays media preview when mediaInfo is present`() {
        val stream = StreamInfo(url = "https://stream1", quality = "1080p", format = "mp4")
        val media = MediaInfo(
            id = "1",
            platform = PlatformId.YOUTUBE,
            title = "Awesome Video Title",
            sourceUrl = "https://test"
        )

        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(
                    urlInput = "https://test",
                    detectedPlatform = PlatformId.YOUTUBE,
                    mediaInfo = media,
                    availableStreams = listOf(stream),
                    selectedStream = stream
                ),
                snackbarHostState = SnackbarHostState(),
                onUrlChange = {},
                onProcessUrl = {},
                onPaste = {},
                onSelectStream = {},
                onStartDownload = {},
                onStartBatchDownload = {}
            )
        }

        composeTestRule.onNodeWithText("Awesome Video Title").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Download").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `HomeScreenContent does not show legacy Login CTA when error mentions Login or Settings`() {
        val message = "Please check Settings or Login status later."
        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(errorMessage = message),
                snackbarHostState = SnackbarHostState(),
                onUrlChange = {},
                onProcessUrl = {},
                onPaste = {},
                onSelectStream = {},
                onStartDownload = {},
                onStartBatchDownload = {}
            )
        }

        composeTestRule.onNodeWithText(message).assertIsDisplayed()
        composeTestRule.onNodeWithText("Open Settings / Login").assertDoesNotExist()
    }
    @Test fun `metadata without resolved streams keeps download disabled`() {
        composeTestRule.setContent {
            HomeScreenContent(uiState = HomeUiState(mediaInfo = MediaInfo("failed", PlatformId.INSTAGRAM, "Preview",
                sourceUrl = "https://instagram.com/reel/failed"), errorMessage = "No playable video"),
                snackbarHostState = SnackbarHostState(), onUrlChange = {}, onProcessUrl = {}, onPaste = {},
                onSelectStream = {}, onStartDownload = {}, onStartBatchDownload = {})
        }
        composeTestRule.onNodeWithText("No playable video").assertExists()
        composeTestRule.onNodeWithText("Download").assertIsNotEnabled()
    }

    @Test
    fun `HomeScreenContent renders playlist selection items and Download Selected button`() {
        val items = listOf(
            PlaylistItem("vid11111111", "Intro Video", "Course Author", durationSeconds = 95, url = "https://www.youtube.com/watch?v=vid11111111"),
            PlaylistItem("vid22222222", "Deep Dive", "Course Author", durationSeconds = 210, url = "https://www.youtube.com/watch?v=vid22222222")
        )
        val playlistMedia = MediaInfo(
            id = "PLui12345678",
            platform = PlatformId.YOUTUBE,
            title = "Android Architecture Series",
            sourceUrl = "https://www.youtube.com/playlist?list=PLui12345678",
            playlistItems = items
        )

        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(
                    urlInput = playlistMedia.sourceUrl,
                    detectedPlatform = PlatformId.YOUTUBE,
                    mediaInfo = playlistMedia,
                    selectedPlaylistVideoIds = setOf("vid11111111", "vid22222222"),
                    playlistQuality = "720p"
                ),
                snackbarHostState = SnackbarHostState(),
                onUrlChange = {},
                onProcessUrl = {},
                onPaste = {},
                onSelectStream = {},
                onStartDownload = {},
                onStartBatchDownload = {}
            )
        }

        composeTestRule.onNodeWithText("2 of 2 selected").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("1. Intro Video").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("2. Deep Dive").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Download Selected (2)").performScrollTo().assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun `HomeScreenContent paginates and filters large playlists cleanly`() {
        val items = (1..60).map { index ->
            val id = "vid%08d".format(index)
            PlaylistItem(
                videoId = id,
                title = "Video $index",
                author = "Channel",
                durationSeconds = 120,
                url = "https://www.youtube.com/watch?v=$id"
            )
        }
        val playlistMedia = MediaInfo(
            id = "PLlarge123456",
            platform = PlatformId.YOUTUBE,
            title = "Large Playlist",
            sourceUrl = "https://www.youtube.com/playlist?list=PLlarge123456",
            playlistItems = items,
            totalPlaylistCount = 60
        )

        composeTestRule.setContent {
            HomeScreenContent(
                uiState = HomeUiState(
                    urlInput = playlistMedia.sourceUrl,
                    detectedPlatform = PlatformId.YOUTUBE,
                    mediaInfo = playlistMedia,
                    selectedPlaylistVideoIds = items.mapTo(linkedSetOf()) { it.videoId },
                    playlistQuality = "720p"
                ),
                snackbarHostState = SnackbarHostState(),
                onUrlChange = {},
                onProcessUrl = {},
                onPaste = {},
                onSelectStream = {},
                onStartDownload = {},
                onStartBatchDownload = {}
            )
        }

        composeTestRule.onNodeWithText("Download Selected (60)").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("60 of 60 selected").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Showing 50 of 60 videos").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("60. Video 60").assertDoesNotExist()

        composeTestRule.onNodeWithText("Show 10 More").performScrollTo().performClick()
        composeTestRule.onNodeWithText("60. Video 60").performScrollTo().assertIsDisplayed()

        composeTestRule.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("59")
        composeTestRule.onNodeWithText("59. Video 59").performScrollTo().assertIsDisplayed()
    }
}
