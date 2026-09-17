package com.mediasaver.platform.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlParserTest {

    private val validVideoId = "dQw4w9WgXcQ"

    @Test
    fun `extractVideoId correctly extracts from standard desktop watch URL`() {
        val url = "https://www.youtube.com/watch?v=$validVideoId"
        assertEquals(validVideoId, YouTubeUrlParser.extractVideoId(url))
        assertTrue(YouTubeUrlParser.isYouTubeUrl(url))
    }

    @Test
    fun `extractVideoId correctly extracts from watch URL with additional query parameters and fragment`() {
        val url = "https://www.youtube.com/watch?feature=youtu.be&v=$validVideoId&t=42s#content"
        assertEquals(validVideoId, YouTubeUrlParser.extractVideoId(url))
        assertTrue(YouTubeUrlParser.isYouTubeUrl(url))
    }

    @Test
    fun `extractVideoId correctly extracts from mobile watch URL`() {
        val url = "https://m.youtube.com/watch?v=$validVideoId"
        assertEquals(validVideoId, YouTubeUrlParser.extractVideoId(url))
        assertTrue(YouTubeUrlParser.isYouTubeUrl(url))
    }

    @Test
    fun `extractVideoId correctly extracts from YouTube Music watch URL`() {
        val url = "https://music.youtube.com/watch?v=$validVideoId"
        assertEquals(validVideoId, YouTubeUrlParser.extractVideoId(url))
        assertTrue(YouTubeUrlParser.isYouTubeUrl(url))
    }

    @Test
    fun `extractVideoId correctly extracts from short link youtu dot be`() {
        val url = "https://youtu.be/$validVideoId?si=random_tracking_token"
        assertEquals(validVideoId, YouTubeUrlParser.extractVideoId(url))
        assertTrue(YouTubeUrlParser.isYouTubeUrl(url))
    }

    @Test
    fun `extractVideoId correctly extracts from YouTube Shorts URL`() {
        val url = "https://www.youtube.com/shorts/$validVideoId?feature=share"
        assertEquals(validVideoId, YouTubeUrlParser.extractVideoId(url))
        assertTrue(YouTubeUrlParser.isYouTubeUrl(url))
    }

    @Test
    fun `extractVideoId correctly extracts from YouTube Live URL`() {
        val url = "https://www.youtube.com/live/$validVideoId"
        assertEquals(validVideoId, YouTubeUrlParser.extractVideoId(url))
        assertTrue(YouTubeUrlParser.isYouTubeUrl(url))
    }

    @Test
    fun `extractVideoId correctly extracts from YouTube Embed URL`() {
        val url = "https://www.youtube.com/embed/$validVideoId"
        assertEquals(validVideoId, YouTubeUrlParser.extractVideoId(url))
        assertTrue(YouTubeUrlParser.isYouTubeUrl(url))
    }

    @Test
    fun `extractVideoId accepts raw 11-character video ID`() {
        assertEquals(validVideoId, YouTubeUrlParser.extractVideoId(validVideoId))
    }

    @Test
    fun `extractVideoId returns null for non-YouTube domains and malformed inputs`() {
        assertNull(YouTubeUrlParser.extractVideoId("https://www.tiktok.com/@creator/video/7123456789"))
        assertNull(YouTubeUrlParser.extractVideoId("https://www.instagram.com/reel/C8xyz123/"))
        assertNull(YouTubeUrlParser.extractVideoId("https://example.com/watch?v=$validVideoId"))
        assertNull(YouTubeUrlParser.extractVideoId("https://youtube.com/about"))
        assertNull(YouTubeUrlParser.extractVideoId(""))
        assertNull(YouTubeUrlParser.extractVideoId("   "))

        assertFalse(YouTubeUrlParser.isYouTubeUrl("https://www.tiktok.com/@creator/video/7123456789"))
        assertFalse(YouTubeUrlParser.isYouTubeUrl(""))
    }

    @Test
    fun `buildCanonicalWatchUrl returns normalized https watch URL`() {
        val canonical = YouTubeUrlParser.buildCanonicalWatchUrl(validVideoId)
        assertEquals("https://www.youtube.com/watch?v=$validVideoId", canonical)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildCanonicalWatchUrl throws for invalid video ID length`() {
        YouTubeUrlParser.buildCanonicalWatchUrl("tooShort")
    }
}
