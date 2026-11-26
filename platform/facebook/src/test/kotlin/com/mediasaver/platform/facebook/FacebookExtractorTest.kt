package com.mediasaver.platform.facebook

import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import com.mediasaver.core.network.interceptor.PlatformWebDomain
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FacebookExtractorTest {
    private val server = MockWebServer()
    private lateinit var client: OkHttpClient
    private lateinit var extractor: FacebookExtractor

    @Before fun setUp() {
        server.start()
        // Only the test transport maps the approved HTTPS origin onto the local fixture server.
        client = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            check(original.url.isHttps && original.url.host == "www.facebook.com")
            check(original.tag(PlatformWebDomain::class.java) != null)

            val local = server.url("/").newBuilder()
                .encodedPath(original.url.encodedPath)
                .encodedQuery(original.url.encodedQuery)
                .build()

            chain.proceed(original.newBuilder().url(local).build())
                .newBuilder().request(original).build()
        }.build()
        extractor = FacebookExtractor(client)
    }

    @After fun tearDown() = server.shutdown()

    private fun page(html: String) {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setHeader("Set-Cookie", "test=discard; Path=/")
                .setBody(html)
        )
    }

    @Test fun readsEscapedHdAndSdAndMetadata() = runBlocking {
        page("""
            <html><head>
              <meta property="og:title" content="Music &amp; Dance">
              <meta content="https://s.fbcdn.net/cover.jpg" property="og:image">
              <meta name="author" content="Amina">
            </head><body><script type="application/json">
              {"browser_native_hd_url":"https:\/\/s.fbcdn.net\/hd.mp4?a=1\u0026b=\u00252F",
               "playable_url":"https:\/\/video.xx.fbcdn.net\/sd.mp4?a=1&amp;b=2"}
            </script></body></html>
        """.trimIndent())

        val video = extractor.extract("123").getOrThrow()
        assertEquals("https://s.fbcdn.net/hd.mp4?a=1&b=%2F", video.hdUrl)
        assertEquals("https://video.xx.fbcdn.net/sd.mp4?a=1&b=2", video.sdUrl)
        assertEquals("Music & Dance", video.title)
        assertEquals("Amina", video.author)
        assertEquals("https://s.fbcdn.net/cover.jpg", video.thumbnailUrl)

        val request = server.takeRequest()
        assertEquals("/watch/?v=123", request.path)
        assertEquals("navigate", request.getHeader("Sec-Fetch-Mode"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test fun usesOpenGraphFallbackAndCachesAcrossAdapterCalls() = runBlocking {
        page("""
            <head><title>Harbour</title>
            <meta content='https://s.fbcdn.net/clip.mp4' property='og:video'></head>
            <script>{"hd_src":"https://fbcdn.net.example.org/fake.mp4"}</script>
        """.trimIndent())

        val adapter = FacebookAdapter(client, extractor)
        val info = adapter.extractMediaInfo("https://m.facebook.com/reel/123/").getOrThrow()
        assertEquals("Harbour", info.title)

        val stream = adapter.resolveStreams(info.id, info.sourceUrl).getOrThrow().single()
        assertEquals("SD (MP4)", stream.quality)
        assertEquals("https://s.fbcdn.net/clip.mp4", stream.url)
        assertEquals(1, server.requestCount)
    }

    @Test fun rejectsWallsPrivatePagesAndMissingStreams() = runBlocking {
        val stream = """<script>{"sd_src":"https://s.fbcdn.net/placeholder.mp4"}</script>"""
        for (html in listOf(
            "<title>Log in to Facebook</title>$stream",
            "<form id='login_form'></form>$stream",
            "<p>This video is private.</p>$stream",
            "<html></html>",
            """<meta property="og:video" content="http://s.fbcdn.net/insecure.mp4">"""
        )) {
            page(html)
            val error = extractor.extract("123").exceptionOrNull()
            assertEquals(AppError.ExtractionFailed(PlatformId.FACEBOOK), error)
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }
}
