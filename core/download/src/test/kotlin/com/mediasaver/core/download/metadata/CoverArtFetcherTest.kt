package com.mediasaver.core.download.metadata

import com.mediasaver.core.network.interceptor.PlatformWebDomain
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoverArtFetcherTest {
    private val server = MockWebServer()
    private lateinit var fetcher: CoverArtFetcher

    @Before
    fun setUp() {
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request()
            check(original.url.isHttps && original.url.host == "i.ytimg.com")
            check(original.tag(PlatformWebDomain::class.java) != null)

            val local = server.url("/").newBuilder()
                .encodedPath(original.url.encodedPath)
                .encodedQuery(original.url.encodedQuery)
                .build()

            chain.proceed(original.newBuilder().url(local).build())
                .newBuilder().request(original).build()
        }.build()
        fetcher = CoverArtFetcher(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetches valid JPEG and PNG cover art without sending cookies`() = runBlocking {
        val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x01, 0x02)
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02)

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/jpeg")
                .setHeader("Set-Cookie", "track=1; Path=/")
                .setBody(okio.Buffer().write(jpegBytes))
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody(okio.Buffer().write(pngBytes))
        )

        val jpeg = fetcher.fetch("https://i.ytimg.com/vi/abc123/hqdefault.jpg")
        assertNotNull(jpeg)
        assertFalse(jpeg!!.isPng)
        assertArrayEquals(jpegBytes, jpeg.bytes)
        assertNull(server.takeRequest().getHeader("Cookie"))

        val png = fetcher.fetch("https://i.ytimg.com/vi/abc123/hqdefault.png")
        assertNotNull(png)
        assertTrue(png!!.isPng)
        assertArrayEquals(pngBytes, png.bytes)
        assertNull(server.takeRequest().getHeader("Cookie"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `rejects non-https, unapproved domains, oversized images, and non-image payloads`() = runBlocking {
        assertNull(fetcher.fetch("http://i.ytimg.com/vi/abc/hqdefault.jpg"))
        assertNull(fetcher.fetch("https://evil.example.com/cover.jpg"))
        assertNull(fetcher.fetch("https://user:pass@i.ytimg.com/cover.jpg"))
        assertEquals(0, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(fetcher.fetch("https://i.ytimg.com/missing.jpg"))

        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("not an image"))
        assertNull(fetcher.fetch("https://i.ytimg.com/not-image.jpg"))

        val oversized = ByteArray(CoverArtFetcher.MAX_COVER_ART_BYTES.toInt() + 16).apply {
            this[0] = 0xFF.toByte()
            this[1] = 0xD8.toByte()
            this[2] = 0xFF.toByte()
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "image/jpeg")
                .setBody(okio.Buffer().write(oversized))
        )
        assertNull(fetcher.fetch("https://i.ytimg.com/huge.jpg"))
    }
}
