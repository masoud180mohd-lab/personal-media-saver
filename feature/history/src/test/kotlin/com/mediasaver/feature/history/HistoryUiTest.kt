package com.mediasaver.feature.history

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
class HistoryUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `HistoryScreenContent displays empty state when no items`() {
        composeTestRule.setContent {
            HistoryScreenContent(
                uiState = HistoryUiState(completedItems = emptyList()),
                snackbarHostState = SnackbarHostState(),
                onClearAll = {},
                onOpenMedia = {},
                onShareMedia = {},
                onDeleteMedia = {},
                onConfirmDelete = {},
                onCancelDelete = {},
                onConfirmClearAll = {},
                onCancelClearAll = {}
            )
        }

        composeTestRule.onNodeWithText("No Download History").assertIsDisplayed()
    }

    @Test
    fun `HistoryScreenContent displays completed items`() {
        val task = DownloadTask(
            id = "test_id",
            url = "https://youtube.com/watch?v=1",
            platform = PlatformId.YOUTUBE,
            mediaId = "1",
            title = "Completed Video Test",
            status = DownloadStatus.COMPLETED,
            totalBytes = 1000
        )

        composeTestRule.setContent {
            HistoryScreenContent(
                uiState = HistoryUiState(completedItems = listOf(task)),
                snackbarHostState = SnackbarHostState(),
                onClearAll = {},
                onOpenMedia = {},
                onShareMedia = {},
                onDeleteMedia = {},
                onConfirmDelete = {},
                onCancelDelete = {},
                onConfirmClearAll = {},
                onCancelClearAll = {}
            )
        }

        composeTestRule.onNodeWithText("Completed Video Test").assertIsDisplayed()
        composeTestRule.onNodeWithText("Youtube").assertIsDisplayed()
    }

    @Test
    fun `HistoryScreenContent triggers onDeleteMedia when delete is clicked`() {
        val task = DownloadTask(
            id = "test_id",
            url = "https://youtube.com/watch?v=1",
            platform = PlatformId.YOUTUBE,
            mediaId = "1",
            title = "Video to Delete",
            status = DownloadStatus.COMPLETED
        )

        var deleteRequested = false
        composeTestRule.setContent {
            HistoryScreenContent(
                uiState = HistoryUiState(completedItems = listOf(task)),
                snackbarHostState = SnackbarHostState(),
                onClearAll = {},
                onOpenMedia = {},
                onShareMedia = {},
                onDeleteMedia = { deleteRequested = true },
                onConfirmDelete = {},
                onCancelDelete = {},
                onConfirmClearAll = {},
                onCancelClearAll = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Delete item").performClick()
        assertTrue(deleteRequested)
    }

    @Test
    fun `HistoryScreenContent displays clear all dialog when requested`() {
        var clearAllConfirmed = false
        composeTestRule.setContent {
            HistoryScreenContent(
                uiState = HistoryUiState(showClearAllDialog = true),
                snackbarHostState = SnackbarHostState(),
                onClearAll = {},
                onOpenMedia = {},
                onShareMedia = {},
                onDeleteMedia = {},
                onConfirmDelete = {},
                onCancelDelete = {},
                onConfirmClearAll = { clearAllConfirmed = true },
                onCancelClearAll = {}
            )
        }

        composeTestRule.onNodeWithText("Clear all history?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear All").performClick()
        assertTrue(clearAllConfirmed)
    }
}
