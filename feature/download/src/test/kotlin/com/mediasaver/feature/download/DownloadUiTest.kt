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
        composeTestRule.onNodeWithText("500.0 B / 1000.0 B").assertIsDisplayed()
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

        composeTestRule.onNodeWithContentDescription("Cancel download").performClick()
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

        composeTestRule.onNodeWithContentDescription("Retry download").performClick()
        assertTrue(retryCalled)
    }
}
