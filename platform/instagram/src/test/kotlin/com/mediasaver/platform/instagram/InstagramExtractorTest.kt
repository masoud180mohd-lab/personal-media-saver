package com.mediasaver.platform.instagram

import com.mediasaver.core.network.di.NetworkModule
import com.mediasaver.core.network.interceptor.SsrfRedirectInterceptor
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.mediasaver.core.model.error.AppError

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InstagramExtractorTest {

    private val extractor = InstagramExtractor(OkHttpClient())

    @Test
    fun `the production Instagram client has no cookie storage`() {
        val client = NetworkModule.provideOkHttpClient(SsrfRedirectInterceptor())
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
    }

    @Test
    fun `public extraction never reuses a session from a blocked response`() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(403).message("Forbidden").body("".toResponseBody())
                .header("Set-Cookie", "sessionid=fixture; Secure; Path=/").build()
        }.build()
        val publicExtractor = InstagramExtractor(client)
        repeat(2) {
            assertTrue(publicExtractor.extract("public").exceptionOrNull() is AppError.ContentRestricted)
        }
        assertEquals(6, requests.size)
        requests.forEach {
            assertEquals("www.instagram.com", it.url.host)
            assertNull(it.header("Cookie"))
            assertNull(it.header("Authorization"))
        }
        assertTrue(client.cookieJar.loadForRequest(requests.first().url).isEmpty())
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
                    ]}}
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
        
        assertEquals(listOf(0, 1, 2), details.carouselItems.map { it.mediaIndex })
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
