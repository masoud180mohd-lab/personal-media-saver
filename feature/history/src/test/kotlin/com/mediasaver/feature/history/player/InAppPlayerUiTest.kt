package com.mediasaver.feature.history.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w411dp-h915dp")
class InAppPlayerUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleTask = DownloadTask(
        id = "audio_1",
        url = "https://www.youtube.com/watch?v=abc",
        platform = PlatformId.YOUTUBE,
        mediaId = "abc",
        title = "001 - Nah-wu Lesson",
        artist = "Sheikh Ahmad",
        album = "Arabic Series",
        status = DownloadStatus.COMPLETED
    )

    @Test
    fun `MiniAudioPlayerBar displays title artist speed and dispatches actions`() {
        var expanded = false
        var toggled = false
        var closed = false

        val state = PlaybackState(
            currentItem = sampleTask,
            queue = listOf(sampleTask),
            isVideo = false,
            isPlaying = true,
            positionMs = 65_000L,
            durationMs = 300_000L,
            playbackSpeed = 1.5f
        )

        composeTestRule.setContent {
            MiniAudioPlayerBar(
                state = state,
                onExpand = { expanded = true },
                onTogglePlayPause = { toggled = true },
                onClose = { closed = true }
            )
        }

        composeTestRule.onNodeWithText("001 - Nah-wu Lesson").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sheikh Ahmad • 01:05 / 05:00 • 1.5x").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Pause").performClick()
        assertTrue(toggled)

        composeTestRule.onNodeWithContentDescription("Close").performClick()
        assertTrue(closed)

        composeTestRule.onNodeWithText("001 - Nah-wu Lesson").performClick()
        assertTrue(expanded)
    }

    @Test
    fun `FullAudioPlayerSheet displays metadata controls and updates speed`() {
        var selectedSpeed = 1.0f
        var rewound = false
        var forwarded = false
        var nextClicked = false
        var openedExternal = false

        val secondTask = sampleTask.copy(id = "audio_2", title = "002 - Sarf")
        val state = PlaybackState(
            currentItem = sampleTask,
            queue = listOf(sampleTask, secondTask),
            isVideo = false,
            isPlaying = false,
            positionMs = 65_000L,
            durationMs = 300_000L,
            playbackSpeed = 1.0f
        )

        composeTestRule.setContent {
            FullAudioPlayerContent(
                state = state,
                onTogglePlayPause = {},
                onSeekTo = {},
                onRewind10 = { rewound = true },
                onForward10 = { forwarded = true },
                onPrevious = {},
                onNext = { nextClicked = true },
                onSelectSpeed = { selectedSpeed = it },
                onOpenExternal = { openedExternal = true }
            )
        }

        composeTestRule.onNodeWithText("001 - Nah-wu Lesson").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sheikh Ahmad • Arabic Series").assertIsDisplayed()
        composeTestRule.onNodeWithText("01:05").assertIsDisplayed()
        composeTestRule.onNodeWithText("05:00").assertIsDisplayed()

        composeTestRule.onNodeWithText("1.5x").performScrollTo().performClick()
        assertEquals(1.5f, selectedSpeed, 0.001f)

        composeTestRule.onNodeWithContentDescription("Rewind 10 seconds").performScrollTo().performClick()
        assertTrue(rewound)

        composeTestRule.onNodeWithContentDescription("Forward 10 seconds").performScrollTo().performClick()
        assertTrue(forwarded)

        composeTestRule.onNodeWithContentDescription("Next").performScrollTo().performClick()
        assertTrue(nextClicked)

        composeTestRule.onNodeWithText("Open in another app").performScrollTo().performClick()
        assertTrue(openedExternal)
    }
}
