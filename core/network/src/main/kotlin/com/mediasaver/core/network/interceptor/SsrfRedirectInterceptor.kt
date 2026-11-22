package com.mediasaver.core.network.interceptor

import com.mediasaver.core.common.SecurityUtils
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Optional tighter destination constraint for requests to a single platform's public pages. */
class PlatformWebDomain(private val domain: String) {
    fun accepts(host: String) = host == domain || host.endsWith(".$domain")
}

class SsrfRedirectInterceptor(
    private val maxRedirects: Int = SecurityUtils.MAX_REDIRECT_HOPS
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val domain = request.tag(PlatformWebDomain::class.java)
        var redirects = 0
        while (true) {
            validateTargetUrl(request.url, domain)
            val response = chain.proceed(request)
            val location = response.header("Location")
            if (!response.isRedirect || location.isNullOrBlank()) return response

            // Only the terminal response belongs to the caller. Close before resolving,
            // validating, or dispatching the next request, including on the hop limit.
            val code = response.code
            response.close()
            if (++redirects > maxRedirects) throw IOException("Redirect limit exceeded")
            val previous = request.url
            val next = previous.resolve(location) ?: throw IOException("Invalid redirect location")
            request = request.newBuilder().url(next).apply {
                if (previous.scheme != next.scheme || !previous.host.equals(next.host, ignoreCase = true) ||
                    previous.port != next.port) {
                    for (name in SENSITIVE_HEADERS) removeHeader(name)
                }
                if (code in 301..303 && request.method != "HEAD") {
                    method("GET", null)
                    removeHeader("Transfer-Encoding")
                    removeHeader("Content-Length")
                    removeHeader("Content-Type")
                }
            }.build()
        }
    }

    private fun validateTargetUrl(url: HttpUrl, domain: PlatformWebDomain?) {
        if (!url.isHttps) throw IOException("HTTPS is required")
        if (domain != null && !domain.accepts(url.host)) throw IOException("Outside platform domain")
        if (SecurityUtils.isLocalOrPrivateIp(url.host)) throw IOException("Private or local address blocked")
    }

    private companion object {
        val SENSITIVE_HEADERS = listOf("Cookie", "Authorization", "Proxy-Authorization", "X-CSRFToken", "X-IG-App-ID")
    }
}
