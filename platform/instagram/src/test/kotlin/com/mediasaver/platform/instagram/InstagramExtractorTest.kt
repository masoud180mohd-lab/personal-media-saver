package com.mediasaver.platform.instagram

import com.mediasaver.core.model.error.AppError
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InstagramExtractorTest {

    private lateinit var extractor: InstagramExtractor

    @Before
    fun setUp() {
        extractor = InstagramExtractor(OkHttpClient())
    }

    @Test
    fun `parseEmbedHtml successfully extracts video, image, title, and author`() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <a class="Username" href="/creator123/">creator123</a>
                <div class="EmbeddedMedia">
                    <video class="EmbeddedMediaVideo" src="https://scontent.cdninstagram.com/v/t50.2886-16/reel.mp4?test=1&amp;token=xyz"></video>
                    <img class="EmbeddedMediaImage" src="https://scontent.cdninstagram.com/v/t51.2885-15/thumb.jpg?test=1" />
                </div>
                <div class="CaptionText">Amazing sunset in Zanzibar! #travel #sunset</div>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseEmbedHtml("C123ShortCode", sampleHtml)
        assertTrue(result.isSuccess)

        val details = result.getOrThrow()
        assertEquals("C123ShortCode", details.id)
        assertTrue(details.isVideo)
        assertEquals("https://scontent.cdninstagram.com/v/t50.2886-16/reel.mp4?test=1&token=xyz", details.videoUrl)
        assertEquals("https://scontent.cdninstagram.com/v/t51.2885-15/thumb.jpg?test=1", details.thumbnailUrl)
        assertEquals("Amazing sunset in Zanzibar! #travel #sunset", details.title)
        assertEquals("creator123", details.author)
    }

    @Test
    fun `parseEmbedHtml extracts image-only post when no video is present`() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <a class="Username" href="/photographer/">photographer</a>
                <img class="EmbeddedMediaImage" src="https://scontent.cdninstagram.com/v/photo.jpg" />
                <div class="Caption">Beautiful scenery</div>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseEmbedHtml("PhotoCode", sampleHtml)
        assertTrue(result.isSuccess)

        val details = result.getOrThrow()
        assertFalse(details.isVideo)
        assertEquals(null, details.videoUrl)
        assertEquals("https://scontent.cdninstagram.com/v/photo.jpg", details.imageUrl)
        assertEquals("Beautiful scenery", details.title)
    }

    @Test
    fun `parseEmbedHtml returns ContentRestricted when login challenge is detected`() {
        val loginHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Login &bull; Instagram</title></head>
            <body>
                <form action="/accounts/login/">Login to continue</form>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parseEmbedHtml("PrivateCode", loginHtml)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AppError.ContentRestricted)
    }

    @Test
    fun `parsePostHtml extracts metadata from OpenGraph tags`() {
        val ogHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:title" content="John Doe on Instagram: &quot;Coding modern apps&quot;" />
                <meta property="og:video" content="https://scontent.cdninstagram.com/v/video.mp4" />
                <meta property="og:image" content="https://scontent.cdninstagram.com/v/poster.jpg" />
                <meta property="og:description" content="Coding modern apps" />
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val result = extractor.parsePostHtml("OgCode", ogHtml)
        assertTrue(result.isSuccess)

        val details = result.getOrThrow()
        assertTrue(details.isVideo)
        assertEquals("https://scontent.cdninstagram.com/v/video.mp4", details.videoUrl)
        assertEquals("https://scontent.cdninstagram.com/v/poster.jpg", details.thumbnailUrl)
        assertEquals("John Doe", details.author)
    }

    @Test
    fun `parseWebApiResponse extracts video and username from JSON`() {
        val json = """
            {
                "graphql": {
                    "shortcode_media": {
                        "video_url": "https://scontent.cdninstagram.com/v/api_video.mp4",
                        "display_url": "https://scontent.cdninstagram.com/v/api_thumb.jpg",
                        "username": "coder_pro",
                        "text": "Great day"
                    }
                }
            }
        """.trimIndent()

        val result = extractor.parseWebApiResponse("ApiCode", json)
        assertTrue(result.isSuccess)

        val details = result.getOrThrow()
        assertTrue(details.isVideo)
        assertEquals("https://scontent.cdninstagram.com/v/api_video.mp4", details.videoUrl)
        assertEquals("coder_pro", details.author)
    }

    @Test
    fun `parseWebApiResponse maps login_required to ContentRestricted`() {
        val json = """{"message":"login_required","status":"fail"}"""
        val result = extractor.parseWebApiResponse("ApiCode", json)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AppError.ContentRestricted)
    }
}
