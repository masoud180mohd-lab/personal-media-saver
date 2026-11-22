package com.mediasaver.platform.tiktok

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
}
