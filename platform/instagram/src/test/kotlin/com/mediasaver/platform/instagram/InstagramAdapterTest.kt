package com.mediasaver.platform.instagram

import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class InstagramAdapterTest {
    private val paths = mutableListOf<String>()
    private val cover = """<img class="EmbeddedMediaImage" src="https://cdninstagram.com/cover.jpg">"""
    private val og = """<meta property="og:image" content="https://cdninstagram.com/cover.jpg">"""
    private val reel = "https://www.instagram.com/reel/abc/"
    private val post = "https://www.instagram.com/p/abc/"

    private fun adapter(vararg fixtures: Pair<Int, String>): InstagramAdapter {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, html) = fixtures[paths.size]
            paths += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("fixture").body(html.toResponseBody()).build()
        }.build()
        return InstagramAdapter(client, InstagramExtractor(client))
    }

    @Test fun `cover only Reel exhausts public tiers and never resolves a photo`() = runBlocking {
        val adapter = adapter(200 to cover, 200 to og, 200 to "{}")
        val error = adapter.resolveStreams("abc", reel).exceptionOrNull()
        assertTrue(error is AppError.ExtractionFailed)
        assertTrue(error!!.message!!.contains("playable"))
        assertEquals(listOf("/p/abc/embed/captioned/", "/reel/abc/", "/p/abc/"), paths)
    }

    @Test fun `a login wall following a cover returns restricted`() = runBlocking {
        assertTrue(adapter(200 to cover, 403 to "", 200 to "{}").extractMediaInfo(reel).exceptionOrNull() is AppError.ContentRestricted)
    }

    @Test fun `cover in photo cache cannot satisfy a Reel and playable video is cached`() = runBlocking {
        val video = """<meta property="og:video" content="https://cdninstagram.com/video.mp4">"""
        val adapter = adapter(200 to cover, 200 to og, 200 to "{}", 200 to cover, 200 to video)
        assertTrue(adapter.extractMediaInfo(post).isSuccess)
        assertEquals("Preview", adapter.resolveStreams("abc", post).getOrThrow().single().quality)
        assertTrue(adapter.extractMediaInfo(reel).isSuccess)
        assertEquals("video/mp4", adapter.resolveStreams("abc", reel).getOrThrow().single().mimeType)
        assertEquals(5, paths.size)
    }

    @Test fun `og only photo is explicitly a preview and generic embed avatar is ignored`() = runBlocking {
        val adapter = adapter(200 to """<img src="https://cdninstagram.com/avatar.jpg">""", 200 to og, 200 to "{}")
        assertEquals("Preview", adapter.resolveStreams("abc", post).getOrThrow().single().quality)
        assertEquals(3, paths.size)
    }

    @Test fun `structured mobile slides preserve order poster size and signed query`() = runBlocking {
        val json = """
          {"items":[{"carousel_media":[
            {"media_type":1,"image_versions2":{"candidates":[
              {"url":"https:\/\/cdninstagram.com\/large.jpg?a=1\u0026b=2","width":1080,"height":900},
              {"url":"https://cdninstagram.com/small.jpg","width":80,"height":80}]}},
            {"media_type":2,"video_versions":[
              {"url":"https://cdninstagram.com/low.mp4","width":360,"height":200},
              {"url":"https://cdninstagram.com/unsupported","width":3840,"height":2160,"mime_type":"video/webm"},
              {"url":"https://cdninstagram.com/hd.mp4","width":1920,"height":1080}],
             "image_versions2":{"candidates":[{"url":"https://cdninstagram.com/poster.jpg"}]}}
          ]}]}
        """.trimIndent()
        val partial = """<script>{"carousel_media_count":2,"carousel_media":[{"display_url":"https://cdninstagram.com/one.jpg"}]}</script>$cover"""
        val adapter = adapter(200 to partial, 200 to "", 200 to json)
        val slides = adapter.resolveStreams("abc", post).getOrThrow()
        assertEquals(listOf(0, 1), slides.map { it.mediaIndex })
        assertEquals("https://cdninstagram.com/large.jpg?a=1&b=2", slides[0].url)
        assertEquals("https://cdninstagram.com/hd.mp4", slides[1].url)
        assertEquals("https://cdninstagram.com/poster.jpg", slides[1].thumbnailUrl)
    }

    @Test fun `embedded GraphQL photo is full media`() = runBlocking {
        val html = """<script>window.__additionalDataLoaded('/p/abc/',{"gql_data":{"shortcode_media":{
          "display_resources":[{"src":"https://cdninstagram.com/full.jpg","config_width":1080,"config_height":1080}]
        }}});</script>"""
        assertEquals("High Resolution", adapter(200 to html).resolveStreams("abc", post).getOrThrow().single().quality)
    }
}
