package com.mediasaver.platform.youtube.downloader

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class InMemoryCookieJarTest {
    @Test fun `cookies honor host path security expiry and deletion`() {
        val jar = InMemoryCookieJar()
        val url = "https://www.youtube.com/watch/clip".toHttpUrl()
        fun save(header: String) = jar.saveFromResponse(url, listOf(requireNotNull(Cookie.parse(url, header))))
        fun values(target: String) = jar.loadForRequest(target.toHttpUrl()).map { it.value }
        save("a=host; Secure; Path=/watch")
        save("a=domain; Domain=youtube.com; Path=/")
        assertEquals(listOf("host", "domain"), values(url.toString()))
        assertEquals(listOf("domain"), values("http://www.youtube.com/watch/clip"))
        assertEquals(listOf("domain"), values("https://www.youtube.com/watcher"))
        assertEquals(listOf("domain"), values("https://m.youtube.com/watch/clip"))
        assertTrue(values("https://example.org/watch/clip").isEmpty())
        save("a=expired; Secure; Path=/watch; Max-Age=0")
        assertEquals(listOf("domain"), values(url.toString()))
    }
}
