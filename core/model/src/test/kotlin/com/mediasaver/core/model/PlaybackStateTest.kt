package com.mediasaver.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateTest {

    @Test
    fun `formatPlaybackTime formats mm_ss and h_mm_ss cleanly`() {
        assertEquals("00:00", formatPlaybackTime(-500L))
        assertEquals("00:09", formatPlaybackTime(9_000L))
        assertEquals("01:05", formatPlaybackTime(65_000L))
        assertEquals("1:01:01", formatPlaybackTime(3_661_000L))
    }

    @Test
    fun `shouldResetSavedPosition returns true only near end of track`() {
        assertFalse(shouldResetSavedPosition(12_000L, 60_000L))
        assertFalse(shouldResetSavedPosition(54_999L, 60_000L))
        assertTrue(shouldResetSavedPosition(55_000L, 60_000L))
        assertTrue(shouldResetSavedPosition(60_000L, 60_000L))
        assertFalse(shouldResetSavedPosition(10_000L, 0L))
    }

    @Test
    fun `hasNext and hasPrevious reflect item position in queue`() {
        val t1 = DownloadTask("1", "https://example.com/1", PlatformId.YOUTUBE, "m1")
        val t2 = DownloadTask("2", "https://example.com/2", PlatformId.YOUTUBE, "m2")
        val t3 = DownloadTask("3", "https://example.com/3", PlatformId.YOUTUBE, "m3")
        val queue = listOf(t1, t2, t3)

        val atFirst = PlaybackState(currentItem = t1, queue = queue)
        assertFalse(atFirst.hasPrevious)
        assertTrue(atFirst.hasNext)

        val atMiddle = PlaybackState(currentItem = t2, queue = queue)
        assertTrue(atMiddle.hasPrevious)
        assertTrue(atMiddle.hasNext)

        val atLast = PlaybackState(currentItem = t3, queue = queue)
        assertTrue(atLast.hasPrevious)
        assertFalse(atLast.hasNext)
    }
}
