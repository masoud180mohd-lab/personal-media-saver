package com.mediasaver.platform.youtube

import com.mediasaver.core.model.StreamInfo
import com.mediasaver.platform.youtube.model.YouTubeDownloadPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubeStreamMapperTest {

    private lateinit var mapper: YouTubeStreamMapper

    @Before
    fun setUp() {
        mapper = YouTubeStreamMapper()
    }

    @Test
    fun `parseHeight parses standard and keyword resolutions accurately`() {
        assertEquals(1080, mapper.parseHeight("1080p"))
        assertEquals(720, mapper.parseHeight("720p60"))
        assertEquals(480, mapper.parseHeight("480p"))
        assertEquals(360, mapper.parseHeight("360p"))
        assertEquals(2160, mapper.parseHeight("highest"))
        assertEquals(2160, mapper.parseHeight("4k"))
        assertEquals(144, mapper.parseHeight("lowest"))
    }

    @Test
    fun `selectOptimalPlan prefers progressive stream when available for 720p and below`() {
        val streams = listOf(
            createStream(quality = "360p", isVideoOnly = false, isAudioOnly = false),
            createStream(quality = "720p", isVideoOnly = false, isAudioOnly = false),
            createStream(quality = "1080p", isVideoOnly = true, isAudioOnly = false),
            createStream(quality = "128 kbps", isVideoOnly = false, isAudioOnly = true, bitrate = 128000)
        )

        val plan = mapper.selectOptimalPlan(streams, preferredQuality = "720p")

        assertNotNull(plan)
        assertTrue(plan is YouTubeDownloadPlan.Progressive)
        val progressivePlan = plan as YouTubeDownloadPlan.Progressive
        assertEquals("720p", progressivePlan.stream.quality)
    }

    @Test
    fun `selectOptimalPlan creates adaptive plan pairing 1080p video with best audio stream`() {
        val streams = listOf(
            createStream(quality = "720p", isVideoOnly = false, isAudioOnly = false),
            createStream(quality = "1080p", isVideoOnly = true, isAudioOnly = false),
            createStream(quality = "64 kbps", isVideoOnly = false, isAudioOnly = true, bitrate = 64000),
            createStream(quality = "160 kbps", isVideoOnly = false, isAudioOnly = true, bitrate = 160000)
        )

        val plan = mapper.selectOptimalPlan(streams, preferredQuality = "1080p")

        assertNotNull(plan)
        assertTrue(plan is YouTubeDownloadPlan.Adaptive)
        val adaptivePlan = plan as YouTubeDownloadPlan.Adaptive
        assertEquals("1080p", adaptivePlan.videoStream.quality)
        assertEquals(160000, adaptivePlan.audioStream.bitrate)
    }

    @Test
    fun `selectOptimalPlan falls back gracefully when requested quality is unavailable`() {
        // Video only has 480p and 360p, user prefers 1080p
        val streams = listOf(
            createStream(quality = "360p", isVideoOnly = false, isAudioOnly = false),
            createStream(quality = "480p", isVideoOnly = true, isAudioOnly = false),
            createStream(quality = "128 kbps", isVideoOnly = false, isAudioOnly = true, bitrate = 128000)
        )

        val plan = mapper.selectOptimalPlan(streams, preferredQuality = "1080p")

        assertNotNull(plan)
        // Should select the closest resolution available (480p)
        assertEquals("480p", plan?.qualityLabel)
    }

    @Test
    fun `selectOptimalPlan returns null for empty stream list`() {
        val plan = mapper.selectOptimalPlan(emptyList(), "720p")
        assertNull(plan)
    }

    private fun createStream(
        quality: String,
        isVideoOnly: Boolean,
        isAudioOnly: Boolean,
        bitrate: Int? = null,
        format: String = "mp4"
    ) = StreamInfo(
        url = "https://mock.googlevideo.com/stream?q=$quality",
        quality = quality,
        format = format,
        mimeType = if (isAudioOnly) "audio/$format" else "video/$format",
        isVideoOnly = isVideoOnly,
        isAudioOnly = isAudioOnly,
        fileSize = 10_000_000L,
        bitrate = bitrate,
        codec = if (isAudioOnly) "mp4a.40.2" else "avc1.640028"
    )
}
