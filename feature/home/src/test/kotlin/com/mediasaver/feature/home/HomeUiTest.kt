package com.mediasaver.feature.home

import com.mediasaver.core.common.PlatformDetector
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiTest {

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
}
