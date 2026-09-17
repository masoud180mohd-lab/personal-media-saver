package com.mediasaver.platform.instagram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramUrlParserTest {

    @Test
    fun `isInstagramUrl recognizes valid Instagram URL variants`() {
        assertTrue(InstagramUrlParser.isInstagramUrl("https://www.instagram.com/p/C3xYz123/"))
        assertTrue(InstagramUrlParser.isInstagramUrl("https://instagram.com/reel/C89abc123?igsh=abc"))
        assertTrue(InstagramUrlParser.isInstagramUrl("https://m.instagram.com/reels/C89abc123/"))
        assertTrue(InstagramUrlParser.isInstagramUrl("https://instagr.am/p/C12345/"))
        assertTrue(InstagramUrlParser.isInstagramUrl("http://www.instagram.com/tv/Ctv999/"))
        assertTrue(InstagramUrlParser.isInstagramUrl("instagram.com/p/C998877/"))
    }

    @Test
    fun `isInstagramUrl rejects non-Instagram URLs`() {
        assertFalse(InstagramUrlParser.isInstagramUrl(""))
        assertFalse(InstagramUrlParser.isInstagramUrl("   "))
        assertFalse(InstagramUrlParser.isInstagramUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(InstagramUrlParser.isInstagramUrl("https://www.tiktok.com/@user/video/1234567890123456789"))
        assertFalse(InstagramUrlParser.isInstagramUrl("https://example.com/p/C3xYz123/"))
    }

    @Test
    fun `extractShortcode extracts correct shortcode from post URLs`() {
        val url = "https://www.instagram.com/p/C4A1b2C3d4E/"
        assertEquals("C4A1b2C3d4E", InstagramUrlParser.extractShortcode(url))
    }

    @Test
    fun `extractShortcode extracts correct shortcode from reel URLs with query parameters`() {
        val url = "https://www.instagram.com/reel/C7xYz987/?utm_source=ig_web_copy_link&igsh=MzRlODBiNWFlZA=="
        assertEquals("C7xYz987", InstagramUrlParser.extractShortcode(url))
    }

    @Test
    fun `extractShortcode extracts correct shortcode from reels plural path`() {
        val url = "https://www.instagram.com/reels/C99ReelsCode/"
        assertEquals("C99ReelsCode", InstagramUrlParser.extractShortcode(url))
    }

    @Test
    fun `extractShortcode extracts correct shortcode from IGTV URLs`() {
        val url = "https://m.instagram.com/tv/B_tvCode123/"
        assertEquals("B_tvCode123", InstagramUrlParser.extractShortcode(url))
    }

    @Test
    fun `extractShortcode extracts correct shortcode from instagr_am short links`() {
        val url = "https://instagr.am/p/B123ShortCode/"
        assertEquals("B123ShortCode", InstagramUrlParser.extractShortcode(url))
    }

    @Test
    fun `extractShortcode returns null for invalid URLs`() {
        assertNull(InstagramUrlParser.extractShortcode("https://www.instagram.com/explore/"))
        assertNull(InstagramUrlParser.extractShortcode("https://www.instagram.com/"))
        assertNull(InstagramUrlParser.extractShortcode("not a url"))
    }

    @Test
    fun `isReel detects reels correctly`() {
        assertTrue(InstagramUrlParser.isReel("https://www.instagram.com/reel/C89abc123/"))
        assertTrue(InstagramUrlParser.isReel("https://www.instagram.com/reels/C89abc123/"))
        assertFalse(InstagramUrlParser.isReel("https://www.instagram.com/p/C89abc123/"))
        assertFalse(InstagramUrlParser.isReel("https://www.instagram.com/tv/C89abc123/"))
    }

    @Test
    fun `buildCanonicalPostUrl and buildCanonicalReelUrl format properly`() {
        assertEquals("https://www.instagram.com/p/C123/", InstagramUrlParser.buildCanonicalPostUrl("C123"))
        assertEquals("https://www.instagram.com/reel/C123/", InstagramUrlParser.buildCanonicalReelUrl("C123"))
        assertEquals("https://www.instagram.com/p/C123/embed/captioned/", InstagramUrlParser.buildEmbedUrl("C123"))
        assertEquals("https://www.instagram.com/p/C123/?__a=1&__d=dis", InstagramUrlParser.buildApiUrl("C123"))
    }
}
