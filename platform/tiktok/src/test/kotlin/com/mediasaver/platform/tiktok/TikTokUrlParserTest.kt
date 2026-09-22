package com.mediasaver.platform.tiktok

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokUrlParserTest {

    private val sampleVideoId = "7123456789012345678"

    @Test
    fun `extractVideoId extracts numeric ID from desktop watch URL`() {
        val url = "https://www.tiktok.com/@creator/video/$sampleVideoId"
        assertEquals(sampleVideoId, TikTokUrlParser.extractVideoId(url))
        assertTrue(TikTokUrlParser.isTikTokUrl(url))
        assertFalse(TikTokUrlParser.isShortUrl(url))
    }

    @Test
    fun `extractVideoId extracts numeric ID from URL with tracking parameters`() {
        val url = "https://www.tiktok.com/@creator/video/$sampleVideoId?_t=8XYZ&_r=1&is_from_webapp=1"
        assertEquals(sampleVideoId, TikTokUrlParser.extractVideoId(url))
        assertTrue(TikTokUrlParser.isTikTokUrl(url))
    }

    @Test
    fun `extractVideoId extracts numeric ID from mobile web URL`() {
        val url = "https://m.tiktok.com/v/$sampleVideoId.html"
        assertEquals(sampleVideoId, TikTokUrlParser.extractVideoId(url))
        assertTrue(TikTokUrlParser.isTikTokUrl(url))
    }

    @Test
    fun `extractVideoId accepts raw numeric video ID`() {
        assertEquals(sampleVideoId, TikTokUrlParser.extractVideoId(sampleVideoId))
    }

    @Test
    fun `isShortUrl identifies vm and vt short links correctly`() {
        val vmUrl = "https://vm.tiktok.com/ZM8abc123/"
        val vtUrl = "https://vt.tiktok.com/ZS8xyz789/"
        val tUrl = "https://www.tiktok.com/t/ZT8abc789/"

        assertTrue(TikTokUrlParser.isShortUrl(vmUrl))
        assertTrue(TikTokUrlParser.isShortUrl(vtUrl))
        assertTrue(TikTokUrlParser.isShortUrl(tUrl))

        assertTrue(TikTokUrlParser.isTikTokUrl(vmUrl))
        assertTrue(TikTokUrlParser.isTikTokUrl(vtUrl))
        assertTrue(TikTokUrlParser.isTikTokUrl(tUrl))
    }

    @Test
    fun `extractVideoId returns null for short links prior to network resolution`() {
        assertNull(TikTokUrlParser.extractVideoId("https://vm.tiktok.com/ZM8abc123/"))
        assertNull(TikTokUrlParser.extractVideoId("https://vt.tiktok.com/ZS8xyz789/"))
    }

    @Test
    fun `isTikTokUrl and extractVideoId reject non-TikTok URLs`() {
        assertNull(TikTokUrlParser.extractVideoId("https://youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(TikTokUrlParser.extractVideoId("https://instagram.com/reel/C8xyz/"))
        assertNull(TikTokUrlParser.extractVideoId("https://example.com"))
        assertNull(TikTokUrlParser.extractVideoId(""))
        assertNull(TikTokUrlParser.extractVideoId("   "))

        assertFalse(TikTokUrlParser.isTikTokUrl("https://youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(TikTokUrlParser.isTikTokUrl("https://example.com"))
        assertFalse(TikTokUrlParser.isTikTokUrl(""))
    }

    @Test
    fun `buildCanonicalWatchUrl constructs standardized web link`() {
        val canonical = TikTokUrlParser.buildCanonicalWatchUrl(sampleVideoId, "cool_creator")
        assertEquals("https://www.tiktok.com/@cool_creator/video/$sampleVideoId", canonical)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCanonicalWatchUrl throws for non-numeric video ID`() {
        TikTokUrlParser.buildCanonicalWatchUrl("invalid_id")
    }

    @Test
    fun `resolveCanonicalUrl resolves real short link`() = kotlinx.coroutines.runBlocking {
        val client = okhttp3.OkHttpClient()
        val resolved = TikTokUrlParser.resolveCanonicalUrl("https://vm.tiktok.com/ZSqvw3hdh/", client)
        val videoId = if (resolved != null) TikTokUrlParser.extractVideoId(resolved) else null
        org.junit.Assert.assertNotNull("Resolved URL should not be null", resolved)
        org.junit.Assert.assertNotNull("Extracted videoId should not be null", videoId)
    }
}
