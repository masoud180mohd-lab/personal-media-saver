package com.mediasaver.core.ui.components

import com.mediasaver.core.model.StreamInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPreviewCardTest {

    @Test
    fun `carousel pager activates for any multiple streams`() {
        val streams = listOf(
            StreamInfo(url = "one", quality = "High Resolution"),
            StreamInfo(url = "two", quality = "HD")
        )

        assertTrue(shouldUseCarouselPager(streams))
    }

    @Test
    fun `carousel pager stays inactive for one or zero streams`() {
        assertFalse(shouldUseCarouselPager(emptyList()))
        assertFalse(shouldUseCarouselPager(listOf(StreamInfo(url = "one", quality = "HD"))))
    }
}
