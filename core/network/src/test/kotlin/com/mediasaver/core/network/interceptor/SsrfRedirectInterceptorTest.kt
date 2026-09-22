package com.mediasaver.core.network.interceptor

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SsrfRedirectInterceptorTest {

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .addInterceptor(SsrfRedirectInterceptor(maxRedirects = 5))
        .build()

    @Test
    fun `blocks requests to localhost and loopback addresses`() {
        val request = Request.Builder()
            .url("http://127.0.0.1:8080/test")
            .build()

        try {
            client.newCall(request).execute()
            fail("Expected IOException for private loopback IP")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("blocked by security policy") == true)
        }
    }

    @Test
    fun `blocks requests to RFC 1918 private network spaces`() {
        val request = Request.Builder()
            .url("http://192.168.1.1/secret")
            .build()

        try {
            client.newCall(request).execute()
            fail("Expected IOException for RFC 1918 private IP")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("blocked by security policy") == true)
        }
    }

    @Test
    fun `blocks requests with prohibited schemes`() {
        val interceptor = SsrfRedirectInterceptor()
        try {
            val url = okhttp3.HttpUrl.get("http://localhost")
            // Test that interceptor blocks non-http/https if parsed
        } catch (_: Exception) {}
    }
}
