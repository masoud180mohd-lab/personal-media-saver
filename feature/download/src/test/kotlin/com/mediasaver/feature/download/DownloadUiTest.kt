package com.mediasaver.feature.download

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.DownloadTask
import com.mediasaver.core.model.PlatformId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class DownloadUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `DownloadScreenContent displays empty state when no active tasks`() {
        composeTestRule.setContent {
            DownloadScreenContent(
                uiState = DownloadUiState(activeTasks = emptyList()),
                snackbarHostState = SnackbarHostState(),
                onCancelDownload = {},
                onRetryDownload = {}
            )
        }

        composeTestRule.onNodeWithText("No Active Downloads").assertIsDisplayed()
    }

    @Test
    fun `DownloadScreenContent displays downloading task correctly`() {
        val task = DownloadTask(
            id = "test_id",
            url = "https://youtube.com/watch?v=1",
            platform = PlatformId.YOUTUBE,
            mediaId = "1",
            title = "Testing Video Download",
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 500,
            totalBytes = 1000
        )

        composeTestRule.setContent {
            DownloadScreenContent(
                uiState = DownloadUiState(activeTasks = listOf(task)),
                snackbarHostState = SnackbarHostState(),
                onCancelDownload = {},
                onRetryDownload = {}
            )
        }

        composeTestRule.onNodeWithText("Testing Video Download").assertIsDisplayed()
        composeTestRule.onNodeWithText("Downloading").assertIsDisplayed()
        // verify formatting
        composeTestRule.onNodeWithText("500 B / 1000 B").assertIsDisplayed()
    }

    @Test
    fun `DownloadScreenContent triggers onCancelDownload when close is clicked`() {
        val task = DownloadTask(
            id = "test_id",
            url = "https://youtube.com/watch?v=1",
            platform = PlatformId.YOUTUBE,
            mediaId = "1",
            title = "Testing Video",
            status = DownloadStatus.DOWNLOADING
        )

        var cancelCalled = false
        composeTestRule.setContent {
            DownloadScreenContent(
                uiState = DownloadUiState(activeTasks = listOf(task)),
                snackbarHostState = SnackbarHostState(),
                onCancelDownload = { cancelCalled = true },
                onRetryDownload = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Cancel").performClick()
        assertTrue(cancelCalled)
    }

    @Test
    fun `DownloadScreenContent triggers onRetryDownload when retry is clicked on failed task`() {
        val task = DownloadTask(
            id = "test_id",
            url = "https://youtube.com/watch?v=1",
            platform = PlatformId.YOUTUBE,
            mediaId = "1",
            title = "Failed Video",
            status = DownloadStatus.FAILED
        )

        var retryCalled = false
        composeTestRule.setContent {
            DownloadScreenContent(
                uiState = DownloadUiState(activeTasks = listOf(task)),
                snackbarHostState = SnackbarHostState(),
                onCancelDownload = {},
                onRetryDownload = { retryCalled = true }
            )
        }

        composeTestRule.onNodeWithContentDescription("Retry").performClick()
        assertTrue(retryCalled)
    }

    @Test
    fun `DownloadScreenContent triggers onPauseDownload on downloading task`() {
        val downloadingTask = DownloadTask(
            id = "dl_id",
            url = "https://youtube.com/watch?v=1",
            platform = PlatformId.YOUTUBE,
            mediaId = "1",
            title = "Active Video",
            status = DownloadStatus.DOWNLOADING,
            downloadedBytes = 250,
            totalBytes = 1000
        )

        var pausedId: String? = null
        composeTestRule.setContent {
            DownloadScreenContent(
                uiState = DownloadUiState(activeTasks = listOf(downloadingTask)),
                snackbarHostState = SnackbarHostState(),
                onCancelDownload = {},
                onRetryDownload = {},
                onPauseDownload = { pausedId = it }
            )
        }

        composeTestRule.onNodeWithContentDescription("Pause").performClick()
        org.junit.Assert.assertEquals("dl_id", pausedId)
    }

    @Test
    fun `DownloadScreenContent displays paused task with saved progress and triggers onResumeDownload`() {
        val pausedTask = DownloadTask(
            id = "paused_id",
            url = "https://youtube.com/watch?v=2",
            platform = PlatformId.YOUTUBE,
            mediaId = "2",
            title = "Paused Video",
            status = DownloadStatus.PAUSED,
            downloadedBytes = 500,
            totalBytes = 1000
        )

        var resumedId: String? = null
        composeTestRule.setContent {
            DownloadScreenContent(
                uiState = DownloadUiState(activeTasks = listOf(pausedTask)),
                snackbarHostState = SnackbarHostState(),
                onCancelDownload = {},
                onRetryDownload = {},
                onResumeDownload = { resumedId = it }
            )
        }

        composeTestRule.onNodeWithText("Paused").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 paused").assertIsDisplayed()
        composeTestRule.onNodeWithText("500 B / 1000 B").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Resume").performClick()
        org.junit.Assert.assertEquals("paused_id", resumedId)
    }

    @Test
    fun `DownloadScreenContent bulk queue controls trigger callbacks`() {
        val tasks = listOf(
            DownloadTask(
                id = "t1",
                url = "https://youtube.com/watch?v=1",
                platform = PlatformId.YOUTUBE,
                mediaId = "1",
                title = "Active Task",
                status = DownloadStatus.DOWNLOADING
            ),
            DownloadTask(
                id = "t2",
                url = "https://youtube.com/watch?v=2",
                platform = PlatformId.YOUTUBE,
                mediaId = "2",
                title = "Paused Task",
                status = DownloadStatus.PAUSED
            ),
            DownloadTask(
                id = "t3",
                url = "https://youtube.com/watch?v=3",
                platform = PlatformId.YOUTUBE,
                mediaId = "3",
                title = "Failed Task",
                status = DownloadStatus.FAILED
            )
        )

        var pauseAllCount = 0
        var resumeAllCount = 0
        var retryFailedCount = 0
        var cancelAllCount = 0

        composeTestRule.setContent {
            DownloadScreenContent(
                uiState = DownloadUiState(activeTasks = tasks),
                snackbarHostState = SnackbarHostState(),
                onCancelDownload = {},
                onRetryDownload = {},
                onPauseAll = { pauseAllCount++ },
                onResumeAll = { resumeAllCount++ },
                onRetryAllFailed = { retryFailedCount++ },
                onCancelAll = { cancelAllCount++ }
            )
        }

        composeTestRule.onNodeWithText("Pause All").performClick()
        composeTestRule.onNodeWithText("Resume All").performClick()
        composeTestRule.onNodeWithText("Retry Failed").performClick()
        composeTestRule.onNodeWithText("Cancel All").performClick()

        org.junit.Assert.assertEquals(1, pauseAllCount)
        org.junit.Assert.assertEquals(1, resumeAllCount)
        org.junit.Assert.assertEquals(1, retryFailedCount)
        org.junit.Assert.assertEquals(1, cancelAllCount)
    }
}
