package com.mediasaver.platform.tiktok

import com.mediasaver.core.model.error.AppError
import com.mediasaver.core.network.EphemeralStreamHeaders
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
class TikTokExtractorTest {
    private val id = "7123456789012345678"
    private val url = "https://www.tiktok.com/@creator/video/$id"
    private val stream = "https://v.tiktokcdn.com/video.mp4"

    private fun extract(html: String, status: Int = 200): Result<TikTokMediaDetails> = runBlocking {
        val hosts = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            hosts.add(chain.request().url.host)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(html.toResponseBody()).build()
        }.build()
        TikTokExtractor(client).extract(url, id).also { assertEquals(listOf("www.tiktok.com"), hosts) }
    }

    @Test fun `reads the nested public rehydration payload`() {
        val html = """
            <script>{"playAddr":"https://v.tiktokcdn.com/unrelated.mp4"}</script>
            <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__">
            {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{
              "desc":"Public clip","author":{"uniqueId":"creator"},"video":{"playAddr":"$stream"}
            }}}}}
            </script>
        """.trimIndent()
        val result = extract(html).getOrThrow()
        assertEquals(stream, result.videoUrl)
        assertEquals("creator", result.author)
        assertEquals("Public clip", result.title)
    }

    @Test fun `reads the requested item from SIGI state`() {
        val html = """
            <script id="SIGI_STATE">
            {"ItemModule":{"other":{"video":{"playAddr":"other"}},"$id":{
              "author":"creator","video":{"playAddr":"$stream"}
            }}}
            </script>
        """.trimIndent()
        val result = extract(html).getOrThrow()
        assertEquals("creator", result.author)
        assertEquals(stream, result.videoUrl)
    }

    @Test fun `unavailable public pages fail without third party fallback`() {
        assertTrue(extract("unrecognized page").exceptionOrNull() is AppError.ExtractionFailed)
        assertTrue(extract("", 403).exceptionOrNull() is AppError.ContentRestricted)
        assertTrue(extract("", 404).exceptionOrNull() is AppError.MediaNotFound)
    }

    @Test fun `api-data prefers watermark-free playAddr extracts video duration and registers only tt_chain_token`() = runBlocking {
        val headers = EphemeralStreamHeaders()
        val playUrl = "https://v16-webapp-prime.tiktok.com/play_nowm.mp4"
        val dlUrl = "https://v16-webapp-prime.tiktok.com/download_wm.mp4"
        val musicUrl = "https://v16m.tiktokcdn.com/audio.mp3"
        val html = """
            <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {"__DEFAULT_SCOPE__":{"webapp.i18n-translation":{"embed_err_unavailable":"Video currently unavailable","webapp_landing_header_account_private":"This account is private"}}}
            </script>
            <script id="api-data" type="application/json">
            {"videoDetail":{"itemInfo":{"itemStruct":{
              "desc":"Zanzibar sunset","author":{"uniqueId":"europeboy999"},
              "video":{"duration":73,"cover":"https://p16-sign.tiktokcdn.com/cover.jpg","playAddr":"$playUrl","downloadAddr":"$dlUrl"},
              "music":{"playUrl":"$musicUrl"}
            }}}}
            </script>
        """.trimIndent()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .addHeader("Set-Cookie", "ttwid=ignore-me; Path=/; HttpOnly")
                .addHeader("Set-Cookie", "msToken=ignore-me-too; Path=/")
                .addHeader("Set-Cookie", "tt_chain_token=chain_abc_123; Path=/; Secure")
                .body(html.toResponseBody()).build()
        }.build()

        val result = TikTokExtractor(client, headers).extract(url, id).getOrThrow()
        assertEquals(playUrl, result.videoUrl)
        assertEquals(dlUrl, result.watermarkedVideoUrl)
        assertEquals(musicUrl, result.audioUrl)
        assertEquals(73, result.durationSeconds)
        assertTrue(result.isWatermarkFree)
        assertEquals(mapOf("Cookie" to "tt_chain_token=chain_abc_123"), headers.get(playUrl))
        assertEquals(mapOf("Cookie" to "tt_chain_token=chain_abc_123"), headers.get(dlUrl))
        assertTrue(headers.get(musicUrl).isEmpty())
    }

    @Test fun `photo slideshow imagePost parses ordered slides and TikTokAdapter caches extraction`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val img0 = "https://p16-sign.tiktokcdn.com/slide0.jpg"
        val img1 = "https://p16-sign.tiktokcdn.com/slide1.jpg"
        val html = """
            <script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
            {"__DEFAULT_SCOPE__":{"webapp.i18n-translation":{"embed_err_unavailable":"Video currently unavailable","webapp_landing_header_account_private":"This account is private"}}}
            </script>
            <script id="api-data" type="application/json">
            {"videoDetail":{"itemInfo":{"itemStruct":{
              "desc":"Photo carousel","author":{"uniqueId":"creator_photos"},
              "imagePost":{"images":[
                {"imageURL":{"urlList":["$img0"]}},
                {"imageURL":{"urlList":["$img1"]}}
              ]}
            }}}}
            </script>
        """.trimIndent()
        val photoCanonical = "https://www.tiktok.com/@creator_photos/photo/$id"
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrls += chain.request().url.toString()
            Response.Builder().request(chain.request().newBuilder().url(photoCanonical).build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(html.toResponseBody()).build()
        }.build()

        val adapter = TikTokAdapter(client, TikTokExtractor(client, EphemeralStreamHeaders()))
        val mediaInfo = adapter.extractMediaInfo("https://vm.tiktok.com/ZSsPhoto/").getOrThrow()
        val streams = adapter.resolveStreams(id, "https://vm.tiktok.com/ZSsPhoto/").getOrThrow()

        assertEquals(photoCanonical, mediaInfo.sourceUrl)
        // 1 HEAD short-link resolution + 1 GET html extraction (cached for resolveStreams)
        assertEquals(2, requestedUrls.size)
        assertEquals(listOf(0, 1), streams.map { it.mediaIndex })
        assertEquals(listOf("Slide 1", "Slide 2"), streams.map { it.quality })
        assertEquals(listOf("image/jpeg", "image/jpeg"), streams.map { it.mimeType })
        assertEquals(listOf(img0, img1), streams.map { it.url })
    }

    @Test fun `SlardarWAF shell returns ContentRestricted`() {
        val wafHtml = """<!DOCTYPE html><html><script id="slardar-config">{"slardarClient":"SlardarWAF"}</script></html>"""
        assertTrue(extract(wafHtml).exceptionOrNull() is AppError.ContentRestricted)
    }
}
