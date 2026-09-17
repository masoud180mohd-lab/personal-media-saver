package com.mediasaver.platform.youtube.downloader

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
 */
@Singleton
class NewPipeDownloader @Inject constructor(
    private val client: OkHttpClient
) : Downloader() {

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder = okhttp3.Request.Builder()
            .url(url)

        headers?.forEach { (headerName, headerValues) ->
            headerValues.forEach { headerValue ->
                requestBuilder.addHeader(headerName, headerValue)
            }
        }

        val requestBody = dataToSend?.toRequestBody("application/octet-stream".toMediaTypeOrNull())

        when (httpMethod.uppercase()) {
            "GET" -> requestBuilder.get()
            "HEAD" -> requestBuilder.head()
            "POST" -> requestBuilder.post(requestBody ?: ByteArray(0).toRequestBody(null))
            "PUT" -> requestBuilder.put(requestBody ?: ByteArray(0).toRequestBody(null))
            else -> requestBuilder.method(httpMethod, requestBody)
        }

        val okResponse = client.newCall(requestBuilder.build()).execute()
        return okResponse.use { response ->
            val responseCode = response.code
            val responseMessage = response.message
            val responseHeaders = response.headers.toMultimap()
            val responseBody = response.body?.string().orEmpty()
            val latestUrl = response.request.url.toString()

            Response(
                responseCode,
                responseMessage,
                responseHeaders,
                responseBody,
                latestUrl
            )
        }
    }
}
