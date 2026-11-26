package com.mediasaver.core.download.metadata

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mediasaver.core.network.interceptor.PlatformWebDomain
import com.mediasaver.core.network.interceptor.SsrfRedirectInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches public thumbnail/cover art bytes for embedding into saved audio files.
 * Enforces HTTPS-only, approved platform CDN hosts, SSRF redirect protection, zero cookies,
 * and a 2 MiB size bound. Failures return null cleanly without failing downloads.
 */
@Singleton
class CoverArtFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val client: OkHttpClient by lazy {
        val builder = okHttpClient.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .cache(null)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)

        if (okHttpClient.interceptors.none { it is SsrfRedirectInterceptor }) {
            builder.interceptors().add(0, SsrfRedirectInterceptor())
        }
        builder.build()
    }

    suspend fun fetch(url: String): CoverArt? = withContext(Dispatchers.IO) {
        val parsed = url.trim().toHttpUrlOrNull()?.takeIf {
            it.isHttps &&
                it.port == 443 &&
                it.username.isEmpty() &&
                it.password.isEmpty() &&
                APPROVED_COVER_DOMAINS.accepts(it.host)
        } ?: return@withContext null

        val request = Request.Builder()
            .url(parsed)
            .tag(PlatformWebDomain::class.java, APPROVED_COVER_DOMAINS)
            .header("Accept", "image/jpeg,image/png,image/webp,image/*;q=0.8")
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            client.awaitBytes(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun OkHttpClient.awaitBytes(request: Request): CoverArt? =
        suspendCancellableCoroutine { continuation ->
            val call = newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.success(null))
                }

                override fun onResponse(call: Call, response: Response) {
                    val cover = try {
                        response.use { res ->
                            if (!res.isSuccessful) return@use null
                            val body = res.body ?: return@use null
                            if (body.source().request(MAX_COVER_ART_BYTES + 1)) return@use null
                            val bytes = body.bytes()
                            if (bytes.isEmpty() || bytes.size.toLong() > MAX_COVER_ART_BYTES) {
                                return@use null
                            }
                            decodeCoverArt(bytes)
                        }
                    } catch (e: CancellationException) {
                        continuation.cancel(e)
                        return
                    } catch (_: Exception) {
                        null
                    }
                    continuation.resumeWith(Result.success(cover))
                }
            })
        }

    private fun decodeCoverArt(bytes: ByteArray): CoverArt? {
        CoverArt.detect(bytes)?.let { return it }
        if (!CoverArt.isWebp(bytes)) return null
        return runCatching {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            try {
                ByteArrayOutputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)) return null
                    CoverArt.detect(out.toByteArray())
                }
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    companion object {
        const val MAX_COVER_ART_BYTES = 2L * 1024 * 1024

        val APPROVED_COVER_DOMAINS = PlatformWebDomain(
            "ytimg.com",
            "ggpht.com",
            "googleusercontent.com",
            "youtube.com",
            "fbcdn.net",
            "cdninstagram.com",
            "instagram.com",
            "facebook.com",
            "tiktokcdn.com",
            "tiktokcdn-us.com",
            "tiktokcdn-eu.com",
            "tiktokv.com",
            "ibytedtos.com",
            "byteoversea.com",
            "muscdn.com",
            "twimg.com"
        )

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
