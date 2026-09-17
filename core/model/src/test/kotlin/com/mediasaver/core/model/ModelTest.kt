package com.mediasaver.core.model

import com.mediasaver.core.model.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    @Test
    fun `DownloadStatus correctly reports terminal and active states`() {
        assertTrue(DownloadStatus.COMPLETED.isTerminal)
        assertTrue(DownloadStatus.FAILED.isTerminal)
        assertTrue(DownloadStatus.CANCELLED.isTerminal)
        assertFalse(DownloadStatus.DOWNLOADING.isTerminal)
        assertFalse(DownloadStatus.QUEUED.isTerminal)

        assertTrue(DownloadStatus.EXTRACTING.isActive)
        assertTrue(DownloadStatus.DOWNLOADING.isActive)
        assertTrue(DownloadStatus.MUXING.isActive)
        assertFalse(DownloadStatus.COMPLETED.isActive)
    }

    @Test
    fun `StreamInfo progressive vs adaptive detection`() {
        val progressive = StreamInfo(
            url = "https://example.com/stream.mp4",
            isVideoOnly = false,
            isAudioOnly = false
        )
        assertTrue(progressive.isProgressive)
        assertFalse(progressive.isAdaptive)

        val videoOnly = StreamInfo(
            url = "https://example.com/video.mp4",
            isVideoOnly = true,
            isAudioOnly = false
        )
        assertFalse(videoOnly.isProgressive)
        assertTrue(videoOnly.isAdaptive)
    }

    @Test
    fun `DownloadTask computes progress percentage accurately`() {
        val task = DownloadTask(
            id = "task1",
            url = "https://example.com",
            platform = PlatformId.YOUTUBE,
            mediaId = "123",
            totalBytes = 1000L,
            downloadedBytes = 500L
        )

        assertEquals(0.5f, task.progressFraction, 0.001f)
        assertEquals(50, task.progressPercent)
    }

    @Test
    fun `AppError delivers user-facing messages`() {
        val invalidUrl = AppError.InvalidUrl("htt://bad")
        assertTrue(invalidUrl.userMessage.contains("invalid or malformed"))

        val networkError = AppError.NetworkError()
        assertTrue(networkError.userMessage.contains("Network connection failed"))

        val extractionFailed = AppError.ExtractionFailed(PlatformId.YOUTUBE)
        assertTrue(extractionFailed.userMessage.contains("YOUTUBE"))
    }
}
