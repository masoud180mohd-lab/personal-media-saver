package com.mediasaver.platform.twitter

import com.mediasaver.core.network.interceptor.PlatformWebDomain
import com.mediasaver.core.network.interceptor.SsrfRedirectInterceptor
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import kotlin.math.PI

object TwitterUrlParser {
    private val pages = setOf(
        "x.com",
        "www.x.com",
        "twitter.com",
        "www.twitter.com",
        "mobile.x.com",
        "mobile.twitter.com"
    )
    private val number = Regex("[0-9]{1,20}")
    private val handle = Regex("[A-Za-z0-9_]{1,15}")
    private val shortcode = Regex("[A-Za-z0-9]+")
    private val suffix = Regex("/(?:video|photo)/[1-9][0-9]*$")

    internal fun validId(value: String) = number.matches(value)

    internal fun https(value: String): HttpUrl? {
        val trimmed = value.trim()
        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", true) || uri.host == null || uri.rawUserInfo != null ||
            (uri.port != -1 && uri.port != 443)
        ) return null
        return trimmed.toHttpUrlOrNull()
    }

    fun extractTweetId(url: String): String? {
        val page = https(url)?.takeIf { it.host in pages } ?: return null
        val path = page.encodedPath.removeSuffix("/").replace(suffix, "").split('/')
        val standard = path.size == 4 && handle.matches(path[1]) && path[2] == "status"
        val web = path.size == 5 && path[1] == "i" && path[2] == "web" && path[3] == "status"
        return path.last().takeIf { (standard || web) && validId(it) }
    }

    fun isShortUrl(url: String): Boolean {
        val page = https(url)?.takeIf { it.host == "t.co" } ?: return false
        return shortcode.matches(page.encodedPath.removePrefix("/").removeSuffix("/"))
    }

    fun isTwitterUrl(url: String) = extractTweetId(url) != null || isShortUrl(url)

    fun buildCanonicalStatusUrl(tweetId: String): String {
        require(validId(tweetId)) { "Expected a numeric tweet ID" }
        return "https://x.com/i/status/$tweetId"
    }

    suspend fun resolveCanonicalUrl(url: String, client: OkHttpClient): String? {
        extractTweetId(url)?.let { return buildCanonicalStatusUrl(it) }
        if (!isShortUrl(url)) return null

        val http = client.twitterClient(pages + "t.co")
        val request = Request.Builder().url(url.trim())
            .tag(PlatformWebDomain::class.java, PlatformWebDomain("t.co", "x.com", "twitter.com"))
            .head().build()

        suspend fun resolve(r: Request) = http.readTwitter(r) {
            it.code to extractTweetId(it.request.url.toString())
        }

        val head = resolve(request)
        val result = if (head.first in setOf(400, 403, 405, 501) || head.second == null) {
            resolve(request.newBuilder().get().build())
        } else head

        return result.second?.takeIf { result.first in 200..299 }?.let(::buildCanonicalStatusUrl)
    }

    fun computeSyndicationToken(tweetId: String): String {
        if (!validId(tweetId)) return "0"
        val value = (tweetId.toDouble() / 1e15) * PI
        var digits = value.toLong()
        var remainder = value - digits
        var tolerance = Math.ulp(value) / 2

        // The token discards the radix point and all zeroes, including leading zeroes.
        while (remainder > 0 && remainder >= tolerance) {
            val scaled = remainder * 36
            val digit = scaled.toInt()
            digits = digits * 36 + digit
            remainder = scaled - digit
            tolerance *= 36
            val roundUp = remainder > 0.5 || (remainder == 0.5 && digit % 2 == 1)
            if (roundUp && remainder + tolerance > 1.0) {
                digits++
                break
            }
        }
        return digits.toString(36).replace("0", "").ifEmpty { "0" }
    }
}

internal const val SYNDICATION_HOST = "cdn.syndication.twimg.com"

internal fun syndicationRequest(url: String) = Request.Builder().url(url)
    .tag(PlatformWebDomain::class.java, PlatformWebDomain(SYNDICATION_HOST))
    .header("Accept", "application/json")

internal fun OkHttpClient.twitterClient(hosts: Set<String>): OkHttpClient = newBuilder()
    .cookieJar(CookieJar.NO_COOKIES).cache(null)
    .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
    .followRedirects(false).followSslRedirects(false)
    .apply {
        val chain = interceptors()
        val guard = chain.filterIsInstance<SsrfRedirectInterceptor>().firstOrNull()
            ?: SsrfRedirectInterceptor()
        chain.removeAll { it is SsrfRedirectInterceptor }
        chain.add(0, guard)
        // The shared guard follows redirects. Check the exact host and port on every hop.
        chain.add(1, Interceptor { hop ->
            val request = hop.request()
            val url = request.url
            if (!url.isHttps || url.port != 443 || url.host !in hosts ||
                url.username.isNotEmpty() || url.password.isNotEmpty()
            ) {
                throw IOException("Unapproved Twitter destination")
            }
            hop.proceed(request)
        })
    }.build()

internal suspend fun <T> OkHttpClient.readTwitter(request: Request, read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) =
                continuation.resumeWith(Result.failure(e))

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    Result.success(response.use(read))
                } catch (e: Exception) {
                    Result.failure(e)
                }
                continuation.resumeWith(result)
            }
        })
    }
