package com.mediasaver.platform.pinterest

import com.mediasaver.core.network.interceptor.PlatformWebDomain
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

internal fun MockWebServer.pinFixtureClient(vararg hosts: String): OkHttpClient =
    OkHttpClient.Builder().cookieJar(object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> = error("Cookie read")
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = error("Cookie write")
    }).addInterceptor { chain ->
        val req = chain.request()
        check(req.url.isHttps && req.url.port == 443 && req.url.host in hosts)
        check(req.tag(PlatformWebDomain::class.java)?.accepts(req.url.host) == true)
        val local = url("/").newBuilder().encodedPath(req.url.encodedPath)
            .encodedQuery(req.url.encodedQuery).build()
        chain.proceed(req.newBuilder().url(local).build()).newBuilder().request(req).build()
    }.build()

class PinterestUrlParserTest {
    private val parser = PinterestUrlParser

    @Test fun canonicalizesWithoutNetwork() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { error("Unexpected HTTP request") }.build()
        for (url in listOf(
            "  https://www.pinterest.com/pin/123/  ",
            "https://pinterest.com/pin/a-blue-bowl--123/",
            "https://uk.pinterest.com/pin/123/sent/x/?ref=home#top",
            "https://www.pinterest.co.uk/pin/123",
            "https://pinterest.de/pin/123",
            "https://www.pinterest.com.mx/pin/123"
        )) {
            assertTrue(url, parser.isPinterestUrl(url))
            assertFalse(url, parser.isShortUrl(url))
            assertEquals("123", parser.extractPinId(url))
            assertEquals("https://www.pinterest.com/pin/123/", parser.resolveCanonicalUrl(url, http))
        }
    }

    @Test fun rejectsMalformedAndSpoofedUrls() {
        for (url in listOf(
            "", "http://pin.it/abc", "https://pin.it.evil.com/abc",
            "https://pinterest.com.evil.com/pin/123", "https://evilpinterest.de/pin/123",
            "https://user@pinterest.fr/pin/123", "https://pinterest.com:8443/pin/123",
            "https://pinterest.com/pin/abc", "https://pinterest.com/pin/name--abc/",
            "https://pinterest.com/pin/123/other", "https://pin.it/a/b"
        )) {
            assertFalse(url, parser.isPinterestUrl(url))
        }
    }

    @Test fun resolvesShortLinksWithHeadOrGet() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val http = server.pinFixtureClient("pin.it", "www.pinterest.fr")
            val short = "https://pin.it/Ab9"
            assertTrue(parser.isShortUrl(short))
            assertNull(parser.extractPinId(short))

            for (code in listOf(302, 200, 400, 403, 405, 501)) {
                if (code != 302) server.enqueue(MockResponse().setResponseCode(code))
                server.enqueue(
                    MockResponse().setResponseCode(302)
                        .addHeader("Location", "https://www.pinterest.fr/pin/blue--123/?ref=share")
                )
                server.enqueue(MockResponse())
                assertEquals(parser.buildCanonicalPinUrl("123"), parser.resolveCanonicalUrl(short, http))
                assertEquals("HEAD", server.takeRequest().method)
                if (code != 302) assertEquals("GET", server.takeRequest().method)
                assertEquals(if (code == 302) "HEAD" else "GET", server.takeRequest().method)
            }
        }
    }

    @Test fun checksEveryRedirectDestination() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val http = server.pinFixtureClient("pin.it")
            for (target in listOf(
                "https://pinterest.com.evil.com/pin/123/",
                "https://pinterest.com:444/pin/123/",
                "https://user@pinterest.com/pin/123/"
            )) {
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target))
                try {
                    parser.resolveCanonicalUrl("https://pin.it/Ab9", http)
                    fail("Unapproved redirect")
                } catch (_: IOException) {
                    assertEquals("/Ab9", server.takeRequest().path)
                }
            }
            assertEquals(3, server.requestCount)
        }
    }
}
