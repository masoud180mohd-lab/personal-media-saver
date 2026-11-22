package com.mediasaver.feature.home

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.mediasaver.core.model.*
import com.mediasaver.core.ui.components.MediaPreviewCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MediaPreviewCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `replacing a carousel resets the page and shrinking to one removes the pager`() {
        val slides = mutableStateOf((0..2).map { StreamInfo("photo$it", mediaIndex = it) })
        val media = MediaInfo("post", PlatformId.INSTAGRAM, "Title", sourceUrl = "https://instagram.com/p/post/")
        compose.setContent { MediaPreviewCard(media, slides.value) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2)
        compose.waitForIdle()
        compose.runOnIdle { slides.value = listOf(StreamInfo("new", mediaIndex = 0), StreamInfo("next", mediaIndex = 1)) }
        compose.onNodeWithText("1/2").assertIsDisplayed()
        compose.runOnIdle { slides.value = slides.value.take(1) }
        compose.onNodeWithText("1/2").assertDoesNotExist()
    }
}
