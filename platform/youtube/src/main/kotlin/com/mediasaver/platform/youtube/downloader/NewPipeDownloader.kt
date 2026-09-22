package com.mediasaver.platform.youtube.downloader

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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

    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    /**
     * Build a dedicated client for NewPipe with:
     * - OkHttp built-in redirect following ENABLED (YouTube API relies heavily on redirects)
     * - Cookie jar for session persistence
     * The parent client's SsrfRedirectInterceptor is still inherited for SSRF protection.
     */
    private val internalClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val host = url.host
                val existing = cookieStore.getOrPut(host) { mutableListOf() }
                synchronized(existing) {
                    cookies.forEach { newCookie ->
                        existing.removeAll { it.name == newCookie.name }
                        existing.add(newCookie)
                    }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val result = mutableListOf<Cookie>()
                cookieStore.forEach { (domain, cookies) ->
                    if (url.host == domain || url.host.endsWith(".$domain")) {
                        synchronized(cookies) {
                            result.addAll(cookies)
                        }
                    }
                }
                return result
            }
        })
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
        if (url.contains("youtube.com") || url.contains("youtu.be") || url.contains("googlevideo.com")) {
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
