package com.mediasaver.core.common

import com.mediasaver.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformDetectorTest {

    @Test
    fun `detects standard YouTube URLs`() {
        val url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals(PlatformId.YOUTUBE, PlatformDetector.detect(url))
    }

    @Test
    fun `detects YouTube short youtu be URLs`() {
        val url = "https://youtu.be/dQw4w9WgXcQ"
        assertEquals(PlatformId.YOUTUBE, PlatformDetector.detect(url))
    }

    @Test
    fun `detects YouTube mobile URLs`() {
        val url = "https://m.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals(PlatformId.YOUTUBE, PlatformDetector.detect(url))
    }

    @Test
    fun `detects YouTube Shorts URLs`() {
        val url = "https://www.youtube.com/shorts/abcdef12345"
        assertEquals(PlatformId.YOUTUBE, PlatformDetector.detect(url))
    }

    @Test
    fun `detects YouTube Live URLs`() {
        val url = "https://www.youtube.com/live/liveStreamId123"
        assertEquals(PlatformId.YOUTUBE, PlatformDetector.detect(url))
    }

    @Test
    fun `detects TikTok standard URLs`() {
        val url = "https://www.tiktok.com/@creator/video/1234567890123456789"
        assertEquals(PlatformId.TIKTOK, PlatformDetector.detect(url))
    }

    @Test
    fun `detects TikTok short vm links`() {
        val url = "https://vm.tiktok.com/ZM8xABCDE/"
        assertEquals(PlatformId.TIKTOK, PlatformDetector.detect(url))
    }

    @Test
    fun `detects TikTok vt links`() {
        val url = "https://vt.tiktok.com/ZS8yFGHIJ/"
        assertEquals(PlatformId.TIKTOK, PlatformDetector.detect(url))
    }

    @Test
    fun `detects Instagram post URLs`() {
        val url = "https://www.instagram.com/p/B_wABCDEFG/"
        assertEquals(PlatformId.INSTAGRAM, PlatformDetector.detect(url))
    }

    @Test
    fun `detects Instagram reel URLs`() {
        val url = "https://www.instagram.com/reel/Cxyz12345/"
        assertEquals(PlatformId.INSTAGRAM, PlatformDetector.detect(url))
    }

    @Test
    fun `detects WhatsApp local URI scheme`() {
        val url = "whatsapp://status/media_item"
        assertEquals(PlatformId.WHATSAPP, PlatformDetector.detect(url))

        val contentUri = "content://com.android.externalstorage.documents/tree/..."
        assertEquals(PlatformId.WHATSAPP, PlatformDetector.detect(contentUri))
    }

    @Test
    fun `returns UNKNOWN for unsupported domains`() {
        assertEquals(PlatformId.UNKNOWN, PlatformDetector.detect("https://vimeo.com/12345"))
        assertEquals(PlatformId.UNKNOWN, PlatformDetector.detect("https://example.com/video.mp4"))
        assertEquals(PlatformId.UNKNOWN, PlatformDetector.detect(""))
    }

    @Test
    fun `isSupported returns true for supported platforms`() {
        assertTrue(PlatformDetector.isSupported("https://www.youtube.com/watch?v=123"))
        assertTrue(PlatformDetector.isSupported("https://vm.tiktok.com/abc"))
        assertTrue(PlatformDetector.isSupported("https://instagram.com/p/xyz"))
        assertFalse(PlatformDetector.isSupported("https://facebook.com/watch/123"))
    }
}
