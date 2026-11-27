package com.mediasaver.platform.facebook

import com.mediasaver.core.network.interceptor.PlatformWebDomain
import com.mediasaver.core.network.interceptor.SsrfRedirectInterceptor
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object FacebookUrlParser {
    private val pages = PlatformWebDomain("facebook.com")
    private val media = PlatformWebDomain("facebook.com", "fbcdn.net")
    private val id = Regex("[0-9]+")
    private val code = Regex("[A-Za-z0-9_-]+")

    internal fun validId(value: String) = id.matches(value)

    private fun https(value: String): HttpUrl? =
        value.trim().toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty()
        }

    internal fun mediaUrl(value: String): String? =
        https(value)?.takeIf { media.accepts(it.host) }?.toString()

    fun extractVideoId(url: String): String? {
        val page = https(url)?.takeIf { pages.accepts(it.host) } ?: return null
        val path = page.pathSegments.dropLastWhile { it.isEmpty() }

        val candidate = when {
            path == listOf("watch") -> page.queryParameterValues("v").singleOrNull()
            path == listOf("story.php") -> page.queryParameterValues("story_fbid").singleOrNull()
            path.size == 2 && path[0] in setOf("videos", "reel") -> path[1]
            path.size == 3 && path[0].isNotEmpty() && path[1] == "videos" -> path[2]
            else -> null
        }
        return candidate?.takeIf(::validId)
    }

    fun isShortShareUrl(url: String): Boolean {
        val page = https(url) ?: return false
        val path = page.pathSegments.dropLastWhile { it.isEmpty() }

        if (page.host == "fb.watch") return path.size == 1 && code.matches(path[0])

        return pages.accepts(page.host) && path.size == 3 &&
            path[0] == "share" && path[1] in setOf("v", "r") && code.matches(path[2])
    }

    fun isFacebookUrl(url: String) = extractVideoId(url) != null || isShortShareUrl(url)

    fun buildCanonicalWatchUrl(videoId: String): String {
        require(validId(videoId)) { "Expected a numeric Facebook video ID" }
        return "https://www.facebook.com/watch/?v=$videoId"
    }

    suspend fun resolveCanonicalUrl(url: String, client: OkHttpClient): String? {
        extractVideoId(url)?.let { return buildCanonicalWatchUrl(it) }
        if (!isShortShareUrl(url)) return null

        val publicClient = client.facebookPublicClient()
        val request = facebookRequest(url).head().build()

        suspend fun resolve(request: Request) = publicClient.readFacebook(request) { response ->
            response.code to extractVideoId(response.request.url.toString())
        }

        var result = resolve(request)
        // Some share endpoints redirect GET requests cleanly but reject/fail HEAD.
        if (result.first in setOf(400, 403, 405, 501) || (result.first in 200..299 && result.second == null)) {
            result = resolve(request.newBuilder().get().build())
        }
        return result.second?.takeIf { result.first in 200..299 }?.let(::buildCanonicalWatchUrl)
    }
}

internal fun OkHttpClient.facebookPublicClient(): OkHttpClient {
    val builder = newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)

    if (interceptors.none { it is SsrfRedirectInterceptor }) {
        builder.interceptors().add(0, SsrfRedirectInterceptor())
    }
    return builder.build()
}

internal fun facebookRequest(url: String): Request.Builder = Request.Builder().url(url)
    .tag(PlatformWebDomain::class.java, PlatformWebDomain("facebook.com", "fb.watch", "fbcdn.net"))
    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
    .header("Accept-Language", "en-US,en;q=0.9")
    .header("Sec-Fetch-Mode", "navigate")
    .header(
        "User-Agent",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    )

internal suspend fun <T> OkHttpClient.readFacebook(request: Request, read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }

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
