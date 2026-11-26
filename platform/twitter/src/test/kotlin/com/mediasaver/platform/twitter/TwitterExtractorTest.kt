package com.mediasaver.platform.twitter

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

class TwitterExtractorTest {
    private val server = MockWebServer()
    private lateinit var extractor: TwitterExtractor
    private lateinit var adapter: TwitterAdapter

    @Before fun setUp() {
        server.start()
        val http = server.fixtureClient(SYNDICATION_HOST)
        extractor = TwitterExtractor(http)
        adapter = TwitterAdapter(http, extractor)
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = server.enqueue(
        MockResponse().addHeader("Content-Type", "application/json")
            .addHeader("Set-Cookie", "fixture=discard; Path=/").setBody(body)
    )

    @Test fun ranksAndDeduplicatesMp4VariantsAndSharesCache() = runBlocking {
        val hi = "https://video.twimg.com/ext/vid/avc1/1920x1080/hi.mp4"
        val lo = "https://video.twimg.com/ext/vid/1280x720/lo.mp4"
        json("""{
          "__typename":"Tweet","id_str":"123","text":"  Ocean\n  view  ","user":{"name":"Asha"},
          "video":{"poster":"https://pbs.twimg.com/p.jpg","durationMs":5100,"variants":[
            {"type":"video/mp4","src":"$hi"},{"type":"application/x-mpegURL","src":"https://video.twimg.com/v.m3u8"}]},
          "mediaDetails":[{"type":"video","video_info":{"variants":[
            {"content_type":"video/mp4","url":"$lo","bitrate":800000},
            {"url":"$hi","bitrate":2500000}]}}]}""")

        val info = async {
            adapter.extractMediaInfo("https://mobile.x.com/asha/status/123/video/1").getOrThrow()
        }
        val streams = adapter.resolveStreams("123", "https://twitter.com/asha/status/123").getOrThrow()

        assertEquals(listOf(hi, lo), streams.map { it.url })
        assertEquals(listOf("1080p (MP4)", "720p (MP4)"), streams.map { it.quality })
        assertEquals(listOf(2500000, 800000), streams.map { it.bitrate })
        assertEquals("Ocean view", info.await().title)
        assertEquals("Asha", info.await().author)
        assertEquals(5, info.await().durationSeconds)
        assertEquals("https://pbs.twimg.com/p.jpg", info.await().thumbnailUrl)
        assertEquals(1, server.requestCount)

        val req = server.takeRequest()
        assertEquals("/tweet-result?id=123&lang=en&token=138spvehogpo", req.path)
        assertNull(req.getHeader("Cookie"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun animatedGifUsesMp4AndAuthorFallback() = runBlocking {
        json("""{"user":{"screen_name":"asha"},"mediaDetails":[{"type":"animated_gif",
          "media_url_https":"https://pbs.twimg.com/g.jpg","video_info":{"duration_millis":2600,
          "variants":[{"content_type":"video/mp4","url":"https://video.twimg.com/g.mp4"}]}}]}""")

        val media = extractor.extract("123").getOrThrow()
        assertEquals("asha", media.author)
        assertEquals(2, media.durationSeconds)
        assertEquals("GIF (MP4)", media.streams.single().quality)
        assertEquals("video/mp4", media.streams.single().mimeType)
    }

    @Test fun photosKeepDisplayOrderAndDiscardUnapprovedHosts() = runBlocking {
        json("""{"mediaDetails":[
          {"type":"photo","media_url_https":"https://pbs.twimg.com/a.jpg"},
          {"type":"photo","media_url_https":"https://pbs.twimg.com.evil.test/b.jpg"},
          {"type":"photo","media_url_https":"https://pbs.twimg.com/c.jpg"}]}""")

        val media = extractor.extract("123").getOrThrow()
        assertEquals(
            listOf("https://pbs.twimg.com/a.jpg", "https://pbs.twimg.com/c.jpg"),
            media.streams.map { it.url }
        )
        assertEquals(listOf(0, 1), media.streams.map { it.mediaIndex })
        assertTrue(media.streams.all { it.mimeType == "image/jpeg" })
        assertEquals(media.streams.first().url, media.thumbnailUrl)
    }

    @Test fun rejectsTombstonesProtectedPostsUntrustedUrlsAndOversizeBodies() = runBlocking {
        val video = """"video":{"variants":[{"src":"https://video.twimg.com/clip.mp4"}]}"""
        for (body in listOf(
            """{"__typename":"TweetTombstone",$video}""",
            """{"user":{"protected":true},$video}""",
            """{"video":{"variants":[{"type":"video/mp4","src":"https://elsewhere.test/a.mp4"}]}}""",
            """{"quoted_tweet":{$video}}""",
            """{"text":"only text"}""",
            """{broken""",
            " ".repeat(2 * 1024 * 1024 + 1)
        )) {
            json(body)
            assertEquals(AppError.ExtractionFailed(PlatformId.TWITTER), extractor.extract("123").exceptionOrNull())
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun mapsHttpFailures() = runBlocking {
        for (code in listOf(404, 410, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            val error = extractor.extract("123").exceptionOrNull()
            if (code == 404 || code == 410) assertTrue(error is AppError.MediaNotFound)
            else assertTrue(error is AppError.NetworkError)
        }
    }
}
