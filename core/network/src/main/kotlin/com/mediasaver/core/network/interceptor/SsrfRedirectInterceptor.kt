package com.mediasaver.core.network.interceptor

import com.mediasaver.core.common.SecurityUtils
import com.mediasaver.core.model.error.AppError
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Interceptor enforcing strict network security policies:
 * 1. Blocks SSRF attacks by rejecting requests and redirects to private, loopback, or local IP spaces.
 * 2. Enforces a strict limit on HTTP redirection hops (max 5) to prevent redirection loops.
 * 3. Restricts request schemes strictly to HTTP and HTTPS.
 */
class SsrfRedirectInterceptor(
    private val maxRedirects: Int = SecurityUtils.MAX_REDIRECT_HOPS
) : Interceptor {

    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        validateTargetUrl(request.url)

        var redirectCount = 0
        var response = chain.proceed(request)

        while (response.isRedirect) {
            redirectCount++
            if (redirectCount > maxRedirects) {
                response.close()
                throw IOException("HTTP redirection loop detected: exceeded maximum limit of $maxRedirects hops")
            }

            val location = response.header("Location")
            if (location.isNullOrBlank()) {
                break
            }

            val nextUrl = request.url.resolve(location)
            if (nextUrl == null) {
                response.close()
                throw IOException("Invalid redirect location URI: $location")
            }

            // Strictly validate the redirected URL before sending any bytes
            validateTargetUrl(nextUrl)

            // Close the previous redirect response to free connection resources
            response.close()

            // Prepare next request, adhering to standard HTTP redirect semantics
            val requestBuilder = request.newBuilder().url(nextUrl)
            if (response.code == 301 || response.code == 302 || response.code == 303) {
                requestBuilder.method("GET", null)
                requestBuilder.removeHeader("Transfer-Encoding")
                requestBuilder.removeHeader("Content-Length")
                requestBuilder.removeHeader("Content-Type")
            }

            request = requestBuilder.build()
            response = chain.proceed(request)
        }

        return response
    }

    private fun validateTargetUrl(url: HttpUrl) {
        val scheme = url.scheme.lowercase()
        if (scheme != "http" && scheme != "https") {
            throw IOException("Prohibited network protocol '$scheme'. Only HTTP and HTTPS are allowed.")
        }

        val host = url.host
        if (SecurityUtils.isLocalOrPrivateIp(host)) {
            throw IOException("Access to private, local, or reserved network host '$host' is blocked by security policy.")
        }
    }
}
