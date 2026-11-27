package com.mediasaver.platform.pinterest

import com.mediasaver.core.network.interceptor.PlatformWebDomain
import com.mediasaver.core.network.interceptor.SsrfRedirectInterceptor
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit

object PinterestUrlParser {
    private val domains = arrayOf(
        "pinterest.com", "pinterest.co.uk", "pinterest.de", "pinterest.fr",
        "pinterest.ca", "pinterest.jp", "pinterest.es", "pinterest.it",
        "pinterest.pt", "pinterest.com.au", "pinterest.com.mx", "pinterest.ph"
    )
    internal val pages = PlatformWebDomain(*domains)
    internal val redirects = PlatformWebDomain(*domains, "pin.it")
    private val code = Regex("[A-Za-z0-9_-]+")

    internal fun validId(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

    internal fun https(s: String): HttpUrl? {
        val trimmed = s.trim()
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", true) || uri.host == null ||
            uri.rawUserInfo != null || uri.port !in setOf(-1, 443)
        ) return null
        return trimmed.toHttpUrlOrNull()
    }

    fun extractPinId(url: String): String? {
        val u = https(url)?.takeIf { pages.accepts(it.host) } ?: return null
        val p = u.pathSegments.dropLastWhile { it.isEmpty() }
        if (p.size < 2 || p[0] != "pin" || (p.size > 2 && p[2] != "sent")) return null
        val segment = p[1]
        if (segment.startsWith("--") || '/' in segment) return null
        return segment.substringAfterLast("--").takeIf(::validId)
    }

    fun isShortUrl(url: String): Boolean {
        val u = https(url)?.takeIf { it.host == "pin.it" } ?: return false
        return code.matches(u.encodedPath.removePrefix("/").removeSuffix("/"))
    }

    fun isPinterestUrl(url: String) = extractPinId(url) != null || isShortUrl(url)

    fun buildCanonicalPinUrl(pinId: String): String {
        require(validId(pinId)) { "Expected a numeric pin ID" }
        return "https://www.pinterest.com/pin/$pinId/"
    }

    suspend fun resolveCanonicalUrl(url: String, client: OkHttpClient): String? {
        extractPinId(url)?.let { return buildCanonicalPinUrl(it) }
        if (!isShortUrl(url)) return null

        val http = client.pinClient()
        val head = pinRequest(url.trim(), redirects).head().build()

        suspend fun resolve(request: Request) = http.readPin(request) {
            it.code to extractPinId(it.request.url.toString())
        }

        val first = resolve(head)
        val last = if (first.first in setOf(400, 403, 405, 501) || first.second == null) {
            resolve(head.newBuilder().get().build())
        } else first

        return last.second?.takeIf { last.first in 200..299 }?.let(::buildCanonicalPinUrl)
    }
}

internal fun pinRequest(url: String, scope: PlatformWebDomain = PinterestUrlParser.pages) =
    Request.Builder().url(url).tag(PlatformWebDomain::class.java, scope)
        .header("Accept", "text/html")
        .header("Sec-Fetch-Mode", "navigate")
        .header(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        )

internal fun OkHttpClient.pinClient() = newBuilder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .cookieJar(CookieJar.NO_COOKIES)
    .cache(null)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(Authenticator.NONE)
    .followRedirects(false)
    .followSslRedirects(false)
    .apply {
        val chain = interceptors()
        val guard = chain.filterIsInstance<SsrfRedirectInterceptor>().firstOrNull()
            ?: SsrfRedirectInterceptor()
        chain.removeAll { it is SsrfRedirectInterceptor }
        chain.add(0, guard)
        // The shared redirect interceptor invokes the next interceptor for each hop.
        chain.add(1, Interceptor { hop ->
            val req = hop.request()
            val u = req.url
            val scope = req.tag(PlatformWebDomain::class.java)
            if (scope?.accepts(u.host) != true || !u.isHttps || u.port != 443 ||
                u.username.isNotEmpty() || u.password.isNotEmpty() ||
                !(PinterestUrlParser.pages.accepts(u.host) || u.host == "pin.it")
            ) {
                throw IOException("Unapproved Pinterest destination")
            }
            hop.proceed(req)
        })
    }.build()

internal suspend fun <T> OkHttpClient.readPin(request: Request, read: (Response) -> T): T =
    suspendCancellableCoroutine { result ->
        val call = newCall(request)
        result.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = result.resumeWith(Result.failure(e))

            override fun onResponse(call: Call, response: Response) {
                val value = try {
                    Result.success(response.use(read))
                } catch (e: Exception) {
                    Result.failure(e)
                }
                result.resumeWith(value)
            }
        })
    }
