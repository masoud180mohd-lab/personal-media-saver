package com.mediasaver.platform.tiktok

import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import com.mediasaver.core.network.EphemeralStreamHeaders
import com.mediasaver.core.network.interceptor.PlatformWebDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracted slide item for a TikTok photo post.
 */
data class TikTokCarouselItem(
    val imageUrl: String,
    val mediaIndex: Int
)

/**
 * Extracted media details from a public TikTok video or photo web page.
 */
data class TikTokMediaDetails(
    val id: String,
    val title: String?,
    val author: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val videoUrl: String? = null,
    val watermarkedVideoUrl: String? = null,
    val audioUrl: String? = null,
    val isWatermarkFree: Boolean = true,
    val sourceUrl: String,
    val photoItems: List<TikTokCarouselItem> = emptyList()
)

/**
 * Lightweight public web extractor for TikTok videos and photo slideshows.
 * Parses public page payloads (`api-data`, `__UNIVERSAL_DATA_FOR_REHYDRATION__`,
 * `SIGI_STATE`, or regex fallbacks) without requiring login, stored cookies, or external services.
 */
@Singleton
class TikTokExtractor @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val ephemeralHeaders: EphemeralStreamHeaders = EphemeralStreamHeaders()
) {

    companion object {
        internal const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

        private val API_DATA_REGEX = Pattern.compile(
            "<script\\s+id=[\"']api-data[\"'][^>]*>([\\s\\S]*?)</script>",
            Pattern.CASE_INSENSITIVE
        )

        private val REHYDRATION_REGEX = Pattern.compile(
            "<script\\s+id=[\"']__UNIVERSAL_DATA_FOR_REHYDRATION__[\"'][^>]*>([\\s\\S]*?)</script>",
            Pattern.CASE_INSENSITIVE
        )

        private val SIGI_STATE_REGEX = Pattern.compile(
            "<script\\s+id=[\"']SIGI_STATE[\"'][^>]*>([\\s\\S]*?)</script>",
            Pattern.CASE_INSENSITIVE
        )

        private val PLAY_ADDR_REGEX = Pattern.compile("\"playAddr\":\\s*\"(https?:[^\"\\s]+)\"")
        private val DOWNLOAD_ADDR_REGEX = Pattern.compile("\"downloadAddr\":\\s*\"(https?:[^\"\\s]+)\"")
        private val COVER_REGEX = Pattern.compile("\"cover\":\\s*\"(https?:[^\"\\s]+)\"")
        private val DESC_REGEX = Pattern.compile("\"desc\":\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"")
        private val AUTHOR_REGEX = Pattern.compile("\"uniqueId\":\\s*\"([a-zA-Z0-9_.-]+)\"")
        private val DURATION_REGEX = Pattern.compile("\"duration\":\\s*(\\d+)")
        private val SCRIPT_TAG_REGEX = Pattern.compile("<script\\b[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE)
    }

    /**
     * Extracts media metadata and stream URLs from a public TikTok URL.
     */
    suspend fun extract(url: String, videoId: String): Result<TikTokMediaDetails> =
        withContext(Dispatchers.IO) {
            extractFromWebScraping(url, videoId)?.let { return@withContext it }

            Result.failure(
                AppError.ExtractionFailed(
                    PlatformId.TIKTOK,
                    cause = Exception("Could not locate playable stream payload for TikTok media.")
                )
            )
        }

    private fun extractFromWebScraping(canonicalUrl: String, videoId: String): Result<TikTokMediaDetails>? {
        val request = Request.Builder()
            .url(canonicalUrl)
            .tag(PlatformWebDomain::class.java, PlatformWebDomain("tiktok.com"))
            .header("User-Agent", MOBILE_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: SocketTimeoutException) {
            return Result.failure(AppError.DownloadTimeout(canonicalUrl))
        } catch (e: IOException) {
            return Result.failure(AppError.NetworkError(cause = e))
        } catch (e: Exception) {
            return Result.failure(AppError.Unknown(cause = e))
        }

        response.use { resp ->
            val code = resp.code
            if (code == 404) {
                return Result.failure(AppError.MediaNotFound(canonicalUrl))
            }
            if (code == 403 || code == 401) {
                return Result.failure(AppError.ContentRestricted("Video is private or unavailable."))
            }
            if (code == 429) {
                return Result.failure(AppError.DownloadFailed(429))
            }
            if (!resp.isSuccessful) {
                return Result.failure(AppError.DownloadFailed(code))
            }

            val chainCookie = extractChainTokenCookie(resp.headers("Set-Cookie"))
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) {
                return Result.failure(
                    AppError.ExtractionFailed(PlatformId.TIKTOK, cause = Exception("Empty response body"))
                )
            }

            val parsed = parseApiData(html, videoId, canonicalUrl)
                ?: parseUniversalData(html, videoId, canonicalUrl)
                ?: parseSigiState(html, videoId, canonicalUrl)
                ?: parseRegexFallback(html, videoId, canonicalUrl)

            if (parsed != null) {
                if (chainCookie != null) {
                    val transientHeaders = mapOf("Cookie" to chainCookie)
                    ephemeralHeaders.put(parsed.videoUrl, transientHeaders)
                    ephemeralHeaders.put(parsed.watermarkedVideoUrl, transientHeaders)
                }
                return Result.success(parsed)
            }

            if (html.contains("\"privateItem\":true")) {
                return Result.failure(
                    AppError.ContentRestricted("This TikTok video or account is private.")
                )
            }

            extractStatusCode(html)?.let { detailCode ->
                when (detailCode) {
                    10216, 10222 -> return Result.failure(
                        AppError.ContentRestricted("This TikTok video or account is private.")
                    )
                    10204, 10217, 10221 -> return Result.failure(
                        AppError.MediaNotFound(canonicalUrl)
                    )
                }
            }

            if (isWafOrCaptchaShell(html)) {
                return Result.failure(
                    AppError.ContentRestricted("TikTok temporarily restricted public access to this page. Please try again later.")
                )
            }

            val visibleHtml = SCRIPT_TAG_REGEX.matcher(html).replaceAll("")
            if (visibleHtml.contains("Video currently unavailable") || visibleHtml.contains("Couldn't find this video")) {
                return Result.failure(AppError.MediaNotFound(canonicalUrl))
            }
            if (visibleHtml.contains("This account is private")) {
                return Result.failure(
                    AppError.ContentRestricted("This TikTok video or account is private.")
                )
            }

            return null
        }
    }

    private fun extractStatusCode(html: String): Int? {
        API_DATA_REGEX.matcher(html).takeIf { it.find() }?.group(1)?.trim()?.let { raw ->
            runCatching { JSONObject(raw).optJSONObject("videoDetail")?.optInt("statusCode", 0) }
                .getOrNull()?.takeIf { it != 0 }?.let { return it }
        }
        REHYDRATION_REGEX.matcher(html).takeIf { it.find() }?.group(1)?.trim()?.let { raw ->
            runCatching {
                val scope = JSONObject(raw).optJSONObject("__DEFAULT_SCOPE__") ?: return@runCatching null
                val detail = scope.optJSONObject("webapp.video-detail")
                    ?: scope.optJSONObject("webapp.reflow.video.detail")
                detail?.optInt("statusCode", 0)
            }.getOrNull()?.takeIf { it != 0 }?.let { return it }
        }
        return null
    }

    private fun extractChainTokenCookie(setCookies: List<String>): String? {
        for (raw in setCookies) {
            val pair = raw.substringBefore(';').trim()
            if (pair.startsWith("tt_chain_token=") && pair.substringAfter('=').isNotBlank()) {
                return pair
            }
        }
        return null
    }

    private fun isWafOrCaptchaShell(html: String): Boolean =
        "SlardarWAF" in html || "slardar_us_waf" in html || "verify_captcha" in html || "secsdk-captcha" in html

    private fun parseApiData(html: String, videoId: String, sourceUrl: String): TikTokMediaDetails? {
        val matcher = API_DATA_REGEX.matcher(html)
        if (!matcher.find()) return null
        val jsonStr = matcher.group(1)?.trim() ?: return null
        return try {
            val root = JSONObject(jsonStr)
            val detail = root.optJSONObject("videoDetail") ?: return null
            val itemStruct = detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct") ?: return null
            parseItemStruct(itemStruct, videoId, sourceUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseUniversalData(html: String, videoId: String, sourceUrl: String): TikTokMediaDetails? {
        val matcher = REHYDRATION_REGEX.matcher(html)
        if (!matcher.find()) return null
        val jsonStr = matcher.group(1)?.trim() ?: return null
        return try {
            val root = JSONObject(jsonStr)
            val defaultScope = root.optJSONObject("__DEFAULT_SCOPE__") ?: return null
            val detail = defaultScope.optJSONObject("webapp.video-detail")
                ?: defaultScope.optJSONObject("webapp.reflow.video.detail")
                ?: return null
            val itemStruct = detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct") ?: return null
            parseItemStruct(itemStruct, videoId, sourceUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSigiState(html: String, videoId: String, sourceUrl: String): TikTokMediaDetails? {
        val matcher = SIGI_STATE_REGEX.matcher(html)
        if (!matcher.find()) return null
        val jsonStr = matcher.group(1)?.trim() ?: return null
        return try {
            val root = JSONObject(jsonStr)
            val itemModule = root.optJSONObject("ItemModule") ?: return null
            val videoItem = itemModule.optJSONObject(videoId) ?: return null
            parseItemStruct(videoItem, videoId, sourceUrl)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseItemStruct(itemStruct: JSONObject, videoId: String, sourceUrl: String): TikTokMediaDetails? {
        val title = itemStruct.optString("desc").takeIf { it.isNotBlank() }
        val author = itemStruct.optJSONObject("author")?.optString("uniqueId")?.takeIf { it.isNotBlank() }
            ?: itemStruct.opt("author")?.let { it as? String }?.takeIf { it.isNotBlank() }

        val videoObj = itemStruct.optJSONObject("video")
        val duration = videoObj?.optInt("duration", 0)?.takeIf { it > 0 }
            ?: itemStruct.optInt("duration", 0).takeIf { it > 0 }

        val imagePost = itemStruct.optJSONObject("imagePost")
        val photos = parsePhotoItems(imagePost)

        val playAddr = videoObj?.let { cleanHttpsUrl(it.optString("playAddr")).ifBlank { bestBitrateUrl(it) } }.orEmpty()
        val downloadAddr = videoObj?.let { cleanHttpsUrl(it.optString("downloadAddr")) }.orEmpty()

        val chosenVideoUrl = playAddr.ifBlank { downloadAddr }.takeIf { it.isNotBlank() }
        if (photos.isEmpty() && chosenVideoUrl == null) return null

        val watermarkedFallback = downloadAddr.takeIf { it.isNotBlank() && it != chosenVideoUrl }
        val isWatermarkFree = playAddr.isNotBlank() && !playAddr.contains("playwm")
        val audioUrl = cleanHttpsUrl(itemStruct.optJSONObject("music")?.optString("playUrl")).takeIf { it.isNotBlank() }

        val cover = listOf(
            videoObj?.optString("cover"),
            videoObj?.optString("originCover"),
            imagePost?.optJSONObject("cover")?.optJSONObject("imageURL")?.optJSONArray("urlList")?.optString(0)
        ).firstNotNullOfOrNull { raw -> cleanHttpsUrl(raw).takeIf { it.isNotBlank() } }
            ?: photos.firstOrNull()?.imageUrl

        return TikTokMediaDetails(
            id = videoId,
            title = title,
            author = author,
            thumbnailUrl = cover,
            durationSeconds = duration,
            videoUrl = chosenVideoUrl,
            watermarkedVideoUrl = watermarkedFallback,
            audioUrl = audioUrl,
            isWatermarkFree = isWatermarkFree,
            sourceUrl = sourceUrl,
            photoItems = photos
        )
    }

    private fun parsePhotoItems(imagePost: JSONObject?): List<TikTokCarouselItem> {
        val images = imagePost?.optJSONArray("images") ?: return emptyList()
        val items = mutableListOf<TikTokCarouselItem>()
        for (i in 0 until images.length()) {
            val entry = images.optJSONObject(i) ?: continue
            val urlList = entry.optJSONObject("imageURL")?.optJSONArray("urlList") ?: continue
            var chosen = ""
            for (uIdx in 0 until urlList.length()) {
                val candidate = cleanHttpsUrl(urlList.optString(uIdx))
                if (candidate.isNotBlank()) {
                    chosen = candidate
                    break
                }
            }
            if (chosen.isNotBlank()) {
                items += TikTokCarouselItem(imageUrl = chosen, mediaIndex = items.size)
            }
        }
        return items
    }

    private fun bestBitrateUrl(videoObj: JSONObject): String {
        val bitrateInfo = videoObj.optJSONArray("bitrateInfo") ?: return ""
        var bestBitrate = -1L
        var bestUrl = ""
        for (i in 0 until bitrateInfo.length()) {
            val entry = bitrateInfo.optJSONObject(i) ?: continue
            val bitrate = entry.optLong("Bitrate", 0L)
            val urls = entry.optJSONObject("PlayAddr")?.optJSONArray("UrlList") ?: continue
            var firstHttps = ""
            for (uIdx in 0 until urls.length()) {
                val candidate = cleanHttpsUrl(urls.optString(uIdx))
                if (candidate.isNotBlank()) {
                    firstHttps = candidate
                    break
                }
            }
            if (firstHttps.isNotBlank() && bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = firstHttps
            }
        }
        return bestUrl
    }

    private fun parseRegexFallback(html: String, videoId: String, sourceUrl: String): TikTokMediaDetails? {
        val downloadMatcher = DOWNLOAD_ADDR_REGEX.matcher(html)
        val playMatcher = PLAY_ADDR_REGEX.matcher(html)

        val playUrl = if (playMatcher.find()) cleanHttpsUrl(playMatcher.group(1)) else ""
        val downloadUrl = if (downloadMatcher.find()) cleanHttpsUrl(downloadMatcher.group(1)) else ""

        val chosenVideoUrl = playUrl.ifBlank { downloadUrl }
        if (chosenVideoUrl.isBlank()) return null

        val coverMatcher = COVER_REGEX.matcher(html)
        val cover = if (coverMatcher.find()) cleanHttpsUrl(coverMatcher.group(1)).takeIf { it.isNotBlank() } else null

        val descMatcher = DESC_REGEX.matcher(html)
        val title = if (descMatcher.find()) descMatcher.group(1)?.unescapeUnicode() else null

        val authorMatcher = AUTHOR_REGEX.matcher(html)
        val author = if (authorMatcher.find()) authorMatcher.group(1) else null

        val durationMatcher = DURATION_REGEX.matcher(html)
        val duration = if (durationMatcher.find()) durationMatcher.group(1)?.toIntOrNull() else null

        val watermarkedFallback = downloadUrl.takeIf { it.isNotBlank() && it != chosenVideoUrl }
        val isWatermarkFree = playUrl.isNotBlank() && !playUrl.contains("playwm")

        return TikTokMediaDetails(
            id = videoId,
            title = title,
            author = author,
            thumbnailUrl = cover,
            durationSeconds = duration,
            videoUrl = chosenVideoUrl,
            watermarkedVideoUrl = watermarkedFallback,
            isWatermarkFree = isWatermarkFree,
            sourceUrl = sourceUrl
        )
    }

    private fun cleanHttpsUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val cleaned = raw.unescapeUnicode()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
        return if (cleaned.toHttpUrlOrNull()?.isHttps == true) cleaned else ""
    }

    private fun String.unescapeUnicode(): String {
        val sb = StringBuilder()
        var i = 0
        while (i < this.length) {
            if (this[i] == '\\' && i + 5 < this.length && this[i + 1] == 'u') {
                val hex = this.substring(i + 2, i + 6)
                try {
                    val charCode = hex.toInt(16)
                    sb.append(charCode.toChar())
                    i += 6
                    continue
                } catch (_: NumberFormatException) {}
            }
            sb.append(this[i])
            i++
        }
        return sb.toString()
    }
}
