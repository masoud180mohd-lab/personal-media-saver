package com.mediasaver.platform.tiktok

import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracted media details from a public TikTok video web page.
 */
data class TikTokMediaDetails(
    val id: String,
    val title: String?,
    val author: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val videoUrl: String,
    val watermarkedVideoUrl: String? = null,
    val audioUrl: String? = null,
    val isWatermarkFree: Boolean = true,
    val sourceUrl: String
)

/**
 * Lightweight public web extractor for TikTok videos.
 * Parses public page payloads (__UNIVERSAL_DATA_FOR_REHYDRATION__, SIGI_STATE, or regex fallbacks)
 * without requiring login, session cookies, or external proprietary APIs.
 */
@Singleton
class TikTokExtractor @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

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
    }

    /**
     * Extracts media metadata and direct video stream from a public TikTok URL.
     * Tier 1: Fast TikWM API (supports both short links & canonical URLs, non-watermarked MP4 + audio)
     * Tier 2: Public Web Scraping Fallback
     */
    suspend fun extract(url: String, videoId: String): Result<TikTokMediaDetails> =
        withContext(Dispatchers.IO) {
            // Tier 1: TikWM API
            extractFromTikWmApi(url, videoId)?.let { return@withContext Result.success(it) }

            // Tier 2: Web Scraping Fallback
            val canonicalUrl = if (videoId.isNotBlank()) TikTokUrlParser.buildCanonicalWatchUrl(videoId) else url
            extractFromWebScraping(canonicalUrl, videoId)?.let { return@withContext it }

            Result.failure(
                AppError.ExtractionFailed(
                    PlatformId.TIKTOK,
                    cause = Exception("Could not locate playable stream payload for TikTok media.")
                )
            )
        }

    private fun extractFromTikWmApi(url: String, videoId: String): TikTokMediaDetails? {
        val encodedUrl = try {
            java.net.URLEncoder.encode(url, "UTF-8")
        } catch (_: Exception) {
            return null
        }

        val request = Request.Builder()
            .url("https://www.tikwm.com/api/?url=$encodedUrl")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (_: Exception) {
            return null
        }

        response.use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = try { JSONObject(body) } catch (_: Exception) { return null }
            if (json.optInt("code", -1) != 0) return null

            val data = json.optJSONObject("data") ?: return null
            val play = cleanUrl(data.optString("play"))
            val wmplay = cleanUrl(data.optString("wmplay"))
            val chosenUrl = play.ifBlank { wmplay }
            if (chosenUrl.isBlank()) return null

            val id = data.optString("id").ifBlank { videoId }
            val title = data.optString("title").takeIf { it.isNotBlank() }
            val authorObj = data.optJSONObject("author")
            val author = authorObj?.optString("unique_id")?.ifBlank { authorObj.optString("nickname") }
            val cover = cleanUrl(data.optString("cover")).ifBlank { cleanUrl(data.optString("origin_cover")) }
            val duration = data.optInt("duration", 0).takeIf { it > 0 }
            val music = cleanUrl(data.optString("music")).takeIf { it.isNotBlank() }

            return TikTokMediaDetails(
                id = id,
                title = title,
                author = author,
                thumbnailUrl = cover.takeIf { it.isNotBlank() },
                durationSeconds = duration,
                videoUrl = chosenUrl,
                watermarkedVideoUrl = wmplay.takeIf { it.isNotBlank() && it != chosenUrl },
                audioUrl = music,
                isWatermarkFree = play.isNotBlank(),
                sourceUrl = url
            )
        }
    }

    private fun extractFromWebScraping(canonicalUrl: String, videoId: String): Result<TikTokMediaDetails>? {
        val request = Request.Builder()
            .url(canonicalUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Mode", "navigate")
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

            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) {
                return Result.failure(
                    AppError.ExtractionFailed(PlatformId.TIKTOK, cause = Exception("Empty response body"))
                )
            }

            // Check for private / deleted video indicators in page text
            if (html.contains("Video currently unavailable") || html.contains("Couldn't find this video")) {
                return Result.failure(AppError.MediaNotFound(canonicalUrl))
            }
            if (html.contains("\"privateItem\":true") || html.contains("This account is private")) {
                return Result.failure(
                    AppError.ContentRestricted("This TikTok video or account is private.")
                )
            }

            // 1. Try __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON parse
            parseUniversalData(html, videoId, canonicalUrl)?.let { return Result.success(it) }

            // 2. Try SIGI_STATE JSON parse
            parseSigiState(html, videoId, canonicalUrl)?.let { return Result.success(it) }

            // 3. Try Regex fallback pattern matching
            parseRegexFallback(html, videoId, canonicalUrl)?.let { return Result.success(it) }

            return null
        }
    }

    private fun parseUniversalData(html: String, videoId: String, sourceUrl: String): TikTokMediaDetails? {
        val matcher = REHYDRATION_REGEX.matcher(html)
        if (!matcher.find()) return null

        val jsonStr = matcher.group(1)?.trim() ?: return null
        return try {
            val root = JSONObject(jsonStr)
            val defaultScope = root.optJSONObject("__DEFAULT_SCOPE__") ?: return null
            val detail = defaultScope.optJSONObject("webapp.video-detail") ?: return null
            val itemInfo = detail.optJSONObject("itemInfo") ?: return null
            val itemStruct = itemInfo.optJSONObject("itemStruct") ?: return null

            val title = itemStruct.optString("desc").takeIf { it.isNotBlank() }
            val author = itemStruct.optJSONObject("author")?.optString("uniqueId")
            val duration = itemStruct.optInt("duration", 0).takeIf { it > 0 }

            val videoObj = itemStruct.optJSONObject("video") ?: return null
            val cover = videoObj.optString("cover").takeIf { it.isNotBlank() }

            // Prefer downloadAddr or non-watermarked playAddr if available
            val downloadAddr = cleanUrl(videoObj.optString("downloadAddr"))
            val playAddr = cleanUrl(videoObj.optString("playAddr"))

            val chosenVideoUrl = downloadAddr.ifBlank { playAddr }
            if (chosenVideoUrl.isBlank()) return null

            val isWatermarkFree = downloadAddr.isNotBlank() || !chosenVideoUrl.contains("playwm")

            TikTokMediaDetails(
                id = videoId,
                title = title,
                author = author,
                thumbnailUrl = cover,
                durationSeconds = duration,
                videoUrl = chosenVideoUrl,
                isWatermarkFree = isWatermarkFree,
                sourceUrl = sourceUrl
            )
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

            val title = videoItem.optString("desc").takeIf { it.isNotBlank() }
            val author = videoItem.optString("author").takeIf { it.isNotBlank() }
            val duration = videoItem.optInt("duration", 0).takeIf { it > 0 }

            val videoObj = videoItem.optJSONObject("video") ?: return null
            val cover = videoObj.optString("cover").takeIf { it.isNotBlank() }

            val downloadAddr = cleanUrl(videoObj.optString("downloadAddr"))
            val playAddr = cleanUrl(videoObj.optString("playAddr"))

            val chosenVideoUrl = downloadAddr.ifBlank { playAddr }
            if (chosenVideoUrl.isBlank()) return null

            val isWatermarkFree = downloadAddr.isNotBlank() || !chosenVideoUrl.contains("playwm")

            TikTokMediaDetails(
                id = videoId,
                title = title,
                author = author,
                thumbnailUrl = cover,
                durationSeconds = duration,
                videoUrl = chosenVideoUrl,
                isWatermarkFree = isWatermarkFree,
                sourceUrl = sourceUrl
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRegexFallback(html: String, videoId: String, sourceUrl: String): TikTokMediaDetails? {
        val downloadMatcher = DOWNLOAD_ADDR_REGEX.matcher(html)
        val playMatcher = PLAY_ADDR_REGEX.matcher(html)

        val downloadUrl = if (downloadMatcher.find()) cleanUrl(downloadMatcher.group(1)) else ""
        val playUrl = if (playMatcher.find()) cleanUrl(playMatcher.group(1)) else ""

        val chosenVideoUrl = downloadUrl.ifBlank { playUrl }
        if (chosenVideoUrl.isBlank()) return null

        val coverMatcher = COVER_REGEX.matcher(html)
        val cover = if (coverMatcher.find()) cleanUrl(coverMatcher.group(1)) else null

        val descMatcher = DESC_REGEX.matcher(html)
        val title = if (descMatcher.find()) descMatcher.group(1)?.unescapeUnicode() else null

        val authorMatcher = AUTHOR_REGEX.matcher(html)
        val author = if (authorMatcher.find()) authorMatcher.group(1) else null

        val durationMatcher = DURATION_REGEX.matcher(html)
        val duration = if (durationMatcher.find()) durationMatcher.group(1)?.toIntOrNull() else null

        val isWatermarkFree = downloadUrl.isNotBlank() || !chosenVideoUrl.contains("playwm")

        return TikTokMediaDetails(
            id = videoId,
            title = title,
            author = author,
            thumbnailUrl = cover,
            durationSeconds = duration,
            videoUrl = chosenVideoUrl,
            isWatermarkFree = isWatermarkFree,
            sourceUrl = sourceUrl
        )
    }

    private fun cleanUrl(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.unescapeUnicode()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
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
