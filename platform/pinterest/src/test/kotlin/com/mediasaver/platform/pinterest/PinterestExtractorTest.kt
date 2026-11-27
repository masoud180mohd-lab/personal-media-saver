package com.mediasaver.platform.pinterest

import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PinterestExtractorTest {
    private val server = MockWebServer()
    private lateinit var extractor: PinterestExtractor
    private lateinit var adapter: PinterestAdapter

    @Before fun setUp() {
        server.start()
        val http = server.pinFixtureClient("www.pinterest.com")
        extractor = PinterestExtractor(http)
        adapter = PinterestAdapter(http, extractor)
    }

    @After fun tearDown() = server.shutdown()

    private fun page(html: String) = server.enqueue(
        MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
            .setHeader("Set-Cookie", "fixture=discard; Path=/").setBody(html)
    )

    @Test fun selectsRequestedPinRanksMp4AndSharesFetch() = runBlocking {
        page("""<script id="__PWS_INITIAL_PROPS__" type="application/json">
          {"initialReduxState":{"pins":{
           "999":{"id":"999","videos":{"video_list":{"V_1080P":{"url":"https://v.pinimg.com/other.mp4"}}}},
           "123":{"id":"123","title":"Stoneware","pinner":{"full_name":"Asha Juma"},
            "images":{"orig":{"url":"https://i.pinimg.com/originals/cover.jpg"}},
            "videos":{"video_list":{
              "V_480P":{"url":"https://v.pinimg.com/low.mp4"},
              "V_EXP7":{"url":"https://v.pinimg.com/list.m3u8"},
              "V_720P":{"url":"https:\/\/v1.pinimg.com\/high.mp4","duration":8250}}}}}}}
          </script>""")

        val info = async { adapter.extractMediaInfo("https://pinterest.de/pin/bowl--123/").getOrThrow() }
        val streams = adapter.resolveStreams("123", "https://pinterest.de/pin/123/").getOrThrow()

        assertEquals(listOf("720p (MP4)", "480p (MP4)"), streams.map { it.quality })
        assertEquals(
            listOf("https://v1.pinimg.com/high.mp4", "https://v.pinimg.com/low.mp4"),
            streams.map { it.url }
        )
        assertEquals("Asha Juma", info.await().author)
        assertEquals("Stoneware", info.await().title)
        assertEquals(8, info.await().durationSeconds)
        assertEquals("https://i.pinimg.com/originals/cover.jpg", info.await().thumbnailUrl)
        assertEquals(1, server.requestCount)

        val request = server.takeRequest()
        assertEquals("/pin/123/", request.path)
        assertEquals("text/html", request.getHeader("Accept"))
        assertEquals("navigate", request.getHeader("Sec-Fetch-Mode"))
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun readsJsonLdVideoAndIsoDuration() = runBlocking {
        page("""<script type="application/ld+json">{"@graph":[
          {"@type":"Organization","name":"Publisher","image":"https://i.pinimg.com/originals/logo.jpg"},
          {"@type":"VideoObject","contentUrl":"https://v.pinimg.com/movie.mp4",
           "name":"Harbour","duration":"PT1M2S","creator":{"name":"Amina"},
           "thumbnailUrl":["https://i.pinimg.com/poster.jpg"]}]}</script>""")

        val pin = extractor.extract("123").getOrThrow()
        assertEquals("https://v.pinimg.com/movie.mp4", pin.streams.single().url)
        assertEquals("video/mp4", pin.streams.single().mimeType)
        assertEquals("Harbour", pin.title)
        assertEquals("https://i.pinimg.com/poster.jpg", pin.thumbnailUrl)
        assertEquals(62, pin.durationSeconds)
        assertEquals("Amina", pin.author)
    }

    @Test fun prefersOriginalImageWhenOnlyHlsIsAvailable() = runBlocking {
        for ((ext, mime, label) in listOf(
            Triple("png", "image/png", "Original (PNG)"),
            Triple("jpg", "image/jpeg", "Original (JPG)")
        )) {
            page("""<meta content='Tea &amp; spice' property='og:title'>
              <meta property="og:image" content="https://i.pinimg.com/236x/thumb.jpg">
              <meta property="og:video" content="https://v.pinimg.com/live.m3u8">
              <script type="application/ld+json">{"@type":"ImageObject",
                "contentUrl":"https://i.pinimg.com/originals/photo.$ext"}</script>""")

            val pin = extractor.extract("123").getOrThrow()
            assertEquals("Tea & spice", pin.title)
            assertEquals("https://i.pinimg.com/originals/photo.$ext", pin.streams.single().url)
            assertEquals(label, pin.streams.single().quality)
            assertEquals(mime, pin.streams.single().mimeType)
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun fallsBackToOpenGraphAndPreservesSignedQuery() = runBlocking {
        page("""<script id="__PWS_INITIAL_PROPS__">{broken</script>
          <meta content="https://v.pinimg.com/a.mp4?one=1&amp;two=2" property="og:video">""")
        assertEquals(
            "https://v.pinimg.com/a.mp4?one=1&two=2",
            extractor.extract("123").getOrThrow().streams.single().url
        )
    }

    @Test fun rejectsUnusableOrOversizedPages() = runBlocking {
        for (html in listOf(
            "<html></html>",
            """<meta property="og:video" content="https://v.pinimg.com.evil.test/a.mp4">""",
            """<meta property="og:image" content="https://user@i.pinimg.com/originals/a.png">""",
            """<meta property="og:image" content="http://i.pinimg.com/originals/a.jpg">""",
            """<script>{"pins":{"987":{"id":"987","images":{"orig":{"url":"https://i.pinimg.com/other.jpg"}}}}}</script>""",
            " ".repeat(4 * 1024 * 1024 + 1)
        )) {
            page(html)
            assertEquals(
                AppError.ExtractionFailed(PlatformId.PINTEREST),
                extractor.extract("123").exceptionOrNull()
            )
        }
    }

    @Test fun mapsHttpFailuresAndChecksHealthWithHead() = runBlocking {
        for (code in listOf(404, 410, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            val error = extractor.extract("123").exceptionOrNull()
            if (code in setOf(404, 410)) assertTrue(error is AppError.MediaNotFound)
            else assertTrue(error is AppError.NetworkError)
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
        server.enqueue(MockResponse())
        assertEquals(AdapterStatus.OPERATIONAL, adapter.healthCheck())
        assertEquals("HEAD", server.takeRequest().method)
    }
}
