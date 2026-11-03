package com.mediasaver.platform.instagram

import okhttp3.OkHttpClient
import com.mediasaver.core.model.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramExtractorTest {

    private val extractor = InstagramExtractor(OkHttpClient())

    @Test
    fun `login walls are rejected as restricted content`() {
        val results = listOf(
            extractor.parseEmbedHtml("shortcode", "<html>Login &bull; Instagram</html>"),
            extractor.parsePostHtml("shortcode", "<html>accounts/login</html>"),
            extractor.parseApiResponseJson("shortcode", "{\"message\":\"login_required\"}")
        )

        results.forEach { result ->
            val error = result.exceptionOrNull()
            assertTrue(error is AppError.ContentRestricted)
            assertTrue((error as AppError.ContentRestricted).reason.contains("not supported"))
        }
    }

    @Test
    fun `public embed video remains available without a session`() {
        val result = extractor.parseEmbedHtml(
            "shortcode",
            """<video src="https://cdn.example/video.mp4"></video>"""
        )

        assertTrue(result.isSuccess)
        assertEquals("https://cdn.example/video.mp4", result.getOrThrow().videoUrl)
    }

    @Test
    fun `test unescapeHtmlEntities decodes standard entities`() {
        val html = "Test &amp; &lt; &gt; &quot; &apos;"
        // We use a reflection-based approach to test the private unescapeHtmlEntities function
        val method = InstagramExtractor::class.java.getDeclaredMethod("unescapeHtmlEntities", String::class.java)
        method.isAccessible = true
        val result = method.invoke(extractor, html) as String
        assertEquals("Test & < > \" '", result)
    }

    @Test
    fun `test unescapeHtmlEntities decodes numeric and hex entities`() {
        val html = "Test &#x2014; &#39;"
        val method = InstagramExtractor::class.java.getDeclaredMethod("unescapeHtmlEntities", String::class.java)
        method.isAccessible = true
        val result = method.invoke(extractor, html) as String
        assertEquals("Test — '", result)
    }
    @Test
    fun `test parsePostHtml with robust sidecar json parsing`() {
        // A realistic snippet of Instagram sidecar JSON within HTML
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta property="og:image" content="https://slide1.jpg" />
                <meta property="og:title" content="Test Title" />
            </head>
            <body>
            <script type="application/json">
            {
                "edge_sidecar_to_children": {
                    "edges": [
                        {
                            "node": {
                                "id": "1",
                                "dimensions": {"height": 1080},
                                "display_url": "https://slide1.jpg",
                                "is_video": false
                            }
                        },
                        {
                            "node": {
                                "id": "2",
                                "dimensions": {"height": 1080},
                                "display_url": "https://slide2.jpg",
                                "video_url": "https://slide2.mp4",
                                "is_video": true
                            }
                        },
                        {
                            "node": {
                                "id": "3",
                                "dimensions": {"height": 1080},
                                "display_url": "https://slide3.jpg",
                                "is_video": false
                            }
                        }
                    ]}}]
            </script>
            </body>
            </html>
        """.trimIndent()

        val result = extractor.parsePostHtml("shortcode", html)
        val details = result.getOrNull()
        
        requireNotNull(details)
        assertEquals("Test Title", details.title)
        
        // We expect 3 carousel items
        assertEquals(3, details.carouselItems.size)
        
        val slide1 = details.carouselItems[0]
        assertEquals("https://slide1.jpg", slide1.imageUrl)
        assertEquals(null, slide1.videoUrl)
        assertEquals(false, slide1.isVideo)

        val slide2 = details.carouselItems[1]
        assertEquals("https://slide2.jpg", slide2.imageUrl)
        assertEquals("https://slide2.mp4", slide2.videoUrl)
        assertEquals(true, slide2.isVideo)
        
        val slide3 = details.carouselItems[2]
        assertEquals("https://slide3.jpg", slide3.imageUrl)
        assertEquals(null, slide3.videoUrl)
        assertEquals(false, slide3.isVideo)
    }
}
