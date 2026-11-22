package com.mediasaver.platform.youtube.downloader

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Custom OkHttp-backed Downloader implementation for NewPipe Extractor.
 * Ensures all network traffic flows through the app's secure, zero-telemetry OkHttpClient.
 * Maintains session cookies across extractor requests and applies desktop browser User-Agent
 * to avoid bot-detection and age/consent redirects on YouTube endpoints.
 */
@Singleton
class NewPipeDownloader @Inject constructor(
    client: OkHttpClient
) : Downloader() {

    companion object {
        private const val TAG = "NewPipeDownloader"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    // Request cookies live only for this process. OkHttp performs domain, path and secure matching.
    private val internalClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(InMemoryCookieJar())
        .build()

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        Log.d(TAG, "execute: $httpMethod $url")

        val requestBuilder = okhttp3.Request.Builder()
            .url(url)

        var hasUserAgent = false
        var hasAcceptLanguage = false
        headers?.forEach { (headerName, headerValues) ->
            if (headerName.equals("User-Agent", ignoreCase = true)) {
                hasUserAgent = true
            }
            if (headerName.equals("Accept-Language", ignoreCase = true)) {
                hasAcceptLanguage = true
            }
            headerValues.forEach { headerValue ->
                requestBuilder.addHeader(headerName, headerValue)
            }
        }

        if (!hasUserAgent) {
            requestBuilder.header("User-Agent", USER_AGENT)
        }
        if (!hasAcceptLanguage) {
            requestBuilder.header("Accept-Language", "en-US,en;q=0.9")
        }

        // YouTube consent and localization cookies to bypass consent walls
        val host = url.toHttpUrl().host
        if (listOf("youtube.com", "youtu.be", "googlevideo.com").any { host == it || host.endsWith(".$it") }) {
            val hasCookieHeader = headers?.keys?.any { it.equals("Cookie", ignoreCase = true) } == true
            if (!hasCookieHeader) {
                requestBuilder.addHeader("Cookie", "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg; CONSENT=PENDING+999")
            }
        }

        val requestBody = dataToSend?.toRequestBody("application/json".toMediaTypeOrNull())

        when (httpMethod.uppercase()) {
            "GET" -> requestBuilder.get()
            "HEAD" -> requestBuilder.head()
            "POST" -> requestBuilder.post(requestBody ?: ByteArray(0).toRequestBody(null))
            "PUT" -> requestBuilder.put(requestBody ?: ByteArray(0).toRequestBody(null))
            else -> requestBuilder.method(httpMethod, requestBody)
        }

        try {
            val okResponse = internalClient.newCall(requestBuilder.build()).execute()
            return okResponse.use { response ->
                val responseCode = response.code
                val responseMessage = response.message
                val responseHeaders = response.headers.toMultimap()
                val responseBody = response.body?.string().orEmpty()
                val latestUrl = response.request.url.toString()

                Log.d(TAG, "response: $responseCode $responseMessage (finalUrl=$latestUrl)")

                Response(
                    responseCode,
                    responseMessage,
                    responseHeaders,
                    responseBody,
                    latestUrl
                )
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error for $url: ${e.message}", e)
            throw e
        }
    }
}

internal class InMemoryCookieJar : CookieJar {
    private val stored = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        stored.removeAll { it.expiresAt <= now }
        for (cookie in cookies) {
            stored.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now) stored.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        stored.removeAll { it.expiresAt <= now }
        return stored.filter { it.matches(url) }.sortedByDescending { it.path.length }
    }
}
