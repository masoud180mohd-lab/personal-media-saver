package com.mediasaver.platform.facebook

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class FacebookUrlParserTest {
    private val parser = FacebookUrlParser

    @Test fun directFormsNormalizeWithoutTracking() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { error("Unexpected network request") }
            .build()
        val canonical = "https://www.facebook.com/watch/?v=123"
        val urls = listOf(
            "https://www.facebook.com/watch/?ref=feed&v=123#comments",
            "https://www.facebook.com/watch?v=123",
            "https://www.facebook.com/reel/123/",
            "https://m.facebook.com/reel/123?tracking=abc",
            "https://facebook.com/alex/videos/123/",
            "https://www.facebook.com/videos/123#fragment",
            "https://www.facebook.com/story.php?story_fbid=123&id=456"
        )

        for (url in urls) {
            assertTrue(url, parser.isFacebookUrl(url))
            assertFalse(url, parser.isShortShareUrl(url))
            assertEquals(url, "123", parser.extractVideoId(url))
            assertEquals(url, canonical, parser.resolveCanonicalUrl(url, client))
        }
    }

    @Test fun shareCodesRequireResolution() {
        for (url in listOf(
            "https://fb.watch/Ab_9-/",
            "https://www.facebook.com/share/r/9X/",
            "https://www.facebook.com/share/v/123/?ref=share"
        )) {
            assertTrue(url, parser.isFacebookUrl(url))
            assertTrue(url, parser.isShortShareUrl(url))
            assertNull(url, parser.extractVideoId(url))
        }
    }

    @Test fun followsPublicShareRedirect() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .message("fixture").body("".toResponseBody())

            if (request.url.host == "fb.watch") {
                response.code(302)
                    .header("Location", "https://m.facebook.com/reel/123/?ref=share#t")
                    .build()
            } else {
                response.code(200).build()
            }
        }.build()

        assertEquals(
            parser.buildCanonicalWatchUrl("123"),
            parser.resolveCanonicalUrl("https://fb.watch/Ab9/", client)
        )
    }

    @Test fun rejectsUnsupportedAndSpoofedUrls() {
        for (url in listOf(
            "", " ", "123", "javascript:alert(1)", "file:///reel/123",
            "http://facebook.com/reel/123", "https://facebook.com.evil.com/reel/123",
            "https://evilfacebook.com/videos/123", "https://facebook.com@evil.com/reel/123",
            "https://user:pass@facebook.com/videos/123", "https://fbcdn.net/videos/123",
            "https://facebook.com/watch?v=abc", "https://facebook.com/watch?v=1&v=2",
            "https://facebook.com/videos/123/extra", "https://facebook.com/share/r/"
        )) {
            assertFalse(url, parser.isFacebookUrl(url))
            assertNull(url, parser.extractVideoId(url))
        }
    }
}
