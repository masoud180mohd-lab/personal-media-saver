package com.mediasaver.platform.twitter

import com.mediasaver.core.network.interceptor.PlatformWebDomain
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

// Preserve the logical HTTPS origin on responses so relative redirects resolve correctly.
internal fun MockWebServer.fixtureClient(vararg allowed: String): OkHttpClient =
    OkHttpClient.Builder().cookieJar(object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> = error("Unexpected cookie read")
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = error("Unexpected cookie write")
    }).addInterceptor { chain ->
        val req = chain.request()
        check(req.url.isHttps && req.url.port == 443 && req.url.host in allowed)
        check(req.tag(PlatformWebDomain::class.java)?.accepts(req.url.host) == true)
        val local = url("/").newBuilder().encodedPath(req.url.encodedPath)
            .encodedQuery(req.url.encodedQuery).build()
        chain.proceed(req.newBuilder().url(local).build()).newBuilder().request(req).build()
    }.build()

class TwitterUrlParserTest {
    private val parser = TwitterUrlParser

    @Test fun recognizesCanonicalMobileAndAttachmentLinks() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { error("Unexpected HTTP request") }.build()
        for (url in listOf(
            "https://x.com/alex/status/123",
            "https://www.x.com/alex/status/123",
            "https://twitter.com/alex/status/123?s=20&t=a",
            "https://www.twitter.com/alex/status/123",
            "https://mobile.twitter.com/alex/status/123/photo/2",
            "https://mobile.x.com/alex/status/123/",
            "https://x.com/i/status/123/video/1#t",
            "https://x.com/i/web/status/123"
        )) {
            assertTrue(url, parser.isTwitterUrl(url))
            assertEquals(url, "123", parser.extractTweetId(url))
            assertEquals(url, "https://x.com/i/status/123", parser.resolveCanonicalUrl(url, http))
        }
    }

    @Test fun rejectsMalformedOrUnapprovedUrls() {
        for (url in listOf(
            "", "http://x.com/u/status/1", "https://x.com.evil.com/u/status/1",
            "https://faketwitter.com/u/status/1", "https://user@x.com/u/status/1",
            "https://x.com:444/u/status/1", "https://x.com/u/status/nope",
            "https://x.com/u/status/123/video/nope", "https://t.co/a/b"
        )) {
            assertFalse(url, parser.isTwitterUrl(url))
        }
    }

    @Test fun resolvesShortLinksWithHeadAndGetFallback() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val http = server.fixtureClient("t.co", "mobile.x.com")
            fun redirect() = MockResponse().setResponseCode(302)
                .addHeader("Location", "https://mobile.x.com/u/status/42/video/1?ref=feed")

            for (headCode in listOf(302, 200, 400, 403, 405, 501)) {
                if (headCode != 302) server.enqueue(MockResponse().setResponseCode(headCode))
                server.enqueue(redirect())
                server.enqueue(MockResponse())

                assertEquals("https://x.com/i/status/42", parser.resolveCanonicalUrl("https://t.co/Ab9", http))
                assertEquals("HEAD", server.takeRequest().method)
                if (headCode != 302) assertEquals("GET", server.takeRequest().method)
                assertEquals(if (headCode == 302) "HEAD" else "GET", server.takeRequest().method)
            }
        }
    }

    @Test fun rejectsUnapprovedRedirectBeforeTransport() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val http = server.fixtureClient("t.co")
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .addHeader("Location", "https://x.com:444/u/status/123")
            )
            try {
                parser.resolveCanonicalUrl("https://t.co/Ab9", http)
                fail("Expected the redirect to be rejected")
            } catch (_: IOException) {
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test fun tokenMatchesJavascriptBase36IncludingFractionalDigits() {
        assertEquals("6dq1a2xwd93", parser.computeSyndicationToken("20"))
        assertEquals("138spvehogpo", parser.computeSyndicationToken("123"))
        assertEquals("4ciy252luba", parser.computeSyndicationToken("1793659050588491954"))
        assertEquals("18ps5lqos4lc", parser.computeSyndicationToken("18446744073709551615"))
        assertEquals("0", parser.computeSyndicationToken("invalid"))
    }
}
