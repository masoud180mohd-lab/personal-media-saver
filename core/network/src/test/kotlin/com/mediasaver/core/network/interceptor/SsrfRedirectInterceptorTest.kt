package com.mediasaver.core.network.interceptor

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SsrfRedirectInterceptorTest {
    private val start = "https://8.8.8.8/start"
    private val sensitive = listOf("Cookie", "Authorization", "Proxy-Authorization", "X-CSRFToken", "X-IG-App-ID")
    private val requests = mutableListOf<Request>()
    private var closed = 0

    // A terminal interceptor supplies fixtures. Public numeric addresses exercise the real
    // validation code without DNS, sockets, or a production localhost exception.
    private fun call(vararg locations: String, from: String = start): Response {
        val client = OkHttpClient.Builder().followRedirects(false)
            .addInterceptor(SsrfRedirectInterceptor())
            .addInterceptor { chain ->
                val location = locations.getOrNull(requests.size)
                val request = chain.request()
                requests.add(request)
                val body = object : ForwardingSource(Buffer()) {
                    override fun close() {
                        closed++
                        super.close()
                    }
                }.buffer().asResponseBody()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (location == null) 200 else 302).message("fixture").body(body)
                    .apply { if (location != null) header("Location", location) }.build()
            }.build()
        val request = Request.Builder().url(from).header("Accept", "video/mp4").apply {
            sensitive.forEach { header(it, "fixture") }
        }.build()
        return client.newCall(request).execute()
    }

    @Test fun `cross-host redirect removes sensitive headers`() {
        call("https://1.1.1.1/end").use {
            assertEquals(1, closed)
            sensitive.forEach { name -> assertNull(requests.last().header(name)) }
            assertEquals("video/mp4", requests.last().header("Accept"))
        }
    }

    @Test fun `port change is cross origin`() {
        call("https://8.8.8.8:8443/end").use {
            sensitive.forEach { name -> assertNull(requests.last().header(name)) }
        }
    }

    @Test fun `same origin keeps request headers`() {
        call("https://8.8.8.8:443/end").use {
            assertEquals(requests.first().headers, requests.last().headers)
        }
    }

    @Test fun `rejects unsafe redirect targets after closing the previous body`() {
        for (target in listOf("https://127.0.0.1", "https://192.168.1.1", "https://[fc00::1]", "http://8.8.8.8", "https://[invalid")) {
            requests.clear()
            closed = 0
            assertThrows(IOException::class.java) { call(target) }
            assertEquals(1, requests.size)
            assertEquals(1, closed)
        }
    }

    @Test fun `unsafe initial requests never reach transport`() {
        for (url in listOf("http://8.8.8.8", "https://localhost", "https://[fd00::1]")) {
            assertThrows(IOException::class.java) { call(from = url) }
        }
        assertTrue(requests.isEmpty())
    }

    @Test fun `closes all six responses when five redirects have already been followed`() {
        assertThrows(IOException::class.java) { call(*Array(6) { "/next" }) }
        assertEquals(6, requests.size)
        assertEquals(6, closed)
    }
}
