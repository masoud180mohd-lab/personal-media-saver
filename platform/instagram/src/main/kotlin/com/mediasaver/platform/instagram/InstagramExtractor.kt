package com.mediasaver.platform.instagram

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.mediasaver.core.network.interceptor.PlatformWebDomain
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracted metadata and stream URLs for an Instagram post or reel.
 */
data class InstagramCarouselItem(
    val videoUrl: String?,
    val imageUrl: String?,
    val isVideo: Boolean,
    val mediaIndex: Int
)

/**
 * Extracted metadata and stream URLs for an Instagram post or reel.
 */
data class InstagramMediaDetails(
    val id: String,
    val title: String?,
    val author: String?,
    val thumbnailUrl: String?,
    val videoUrl: String?,
    val imageUrl: String?,
    val isVideo: Boolean,
    val durationSeconds: Int? = null,
    val isDegradedPreview: Boolean = false,
    val carouselItems: List<InstagramCarouselItem> = emptyList()
)

/**
 * Multi-tier extraction engine for Instagram posts and reels.
 *
 * Tier 1: Public Embed Page (/embed/captioned/)
 * Tier 2: Post/Reel HTML (OpenGraph & embedded JSON)
 * Tier 3: Web API query (?__a=1&__d=dis)
 *
 * Strictly respects zero-telemetry and zero-external-service architecture.
 */
@Singleton
class InstagramExtractor @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
        private const val IG_APP_ID = "936619743392459"
        private const val PUBLIC_RESTRICTION = "Instagram has restricted public access to this post. Try another public link."
    }

    suspend fun extract(shortcode: String, expectVideo: Boolean = false): Result<InstagramMediaDetails> =
        withContext(Dispatchers.IO) {
            val post = if (expectVideo) InstagramUrlParser.buildCanonicalReelUrl(shortcode)
                else InstagramUrlParser.buildCanonicalPostUrl(shortcode)
            val urls = listOf(InstagramUrlParser.buildEmbedUrl(shortcode), post, InstagramUrlParser.buildApiUrl(shortcode))
            var restricted: AppError.ContentRestricted? = null
            var last: Throwable? = null
            var preview: InstagramMediaDetails? = null
            for ((tier, url) in urls.withIndex()) {
                val result = fetch(url, tier, shortcode)
                val found = result.getOrNull()
                val media = if (expectVideo && found != null && found.carouselItems.isNotEmpty()) {
                    val playable = found.carouselItems.filter { it.videoUrl != null }
                    playable.firstOrNull()?.let { found.copy(videoUrl = it.videoUrl, imageUrl = it.imageUrl,
                        thumbnailUrl = it.imageUrl, isVideo = true, carouselItems = playable) }
                } else found
                if (media != null && (!expectVideo || media.videoUrl != null)) {
                    if (!media.isDegradedPreview) return@withContext Result.success(media)
                    preview = media
                }
                val error = result.exceptionOrNull()
                if (error is AppError.ContentRestricted) restricted = error
                if (error is AppError.DownloadFailed && error.httpCode == 429) return@withContext result
                if (error != null) last = error
            }
            if (!expectVideo && preview != null) return@withContext Result.success(preview)
            Result.failure(restricted ?: if (expectVideo) AppError.ExtractionFailed(PlatformId.INSTAGRAM,
                userMessage = "No playable public video stream was found for this Reel.")
                else last ?: AppError.ExtractionFailed(PlatformId.INSTAGRAM))
        }

    private fun fetch(url: String, tier: Int, code: String): Result<InstagramMediaDetails> {
        val request = Request.Builder().url(url).tag(PlatformWebDomain::class.java, PlatformWebDomain("instagram.com"))
            .header("User-Agent", USER_AGENT).header("Referer", "https://www.instagram.com/")
            .apply { if (tier == 2) header("X-IG-App-ID", IG_APP_ID) }.build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 ||
                        response.request.url.pathSegments.any { it in setOf("login", "challenge") } -> restricted()
                    response.code == 404 -> Result.failure(AppError.MediaNotFound(url))
                    !response.isSuccessful -> Result.failure(AppError.DownloadFailed(response.code))
                    else -> {
                        val body = response.body?.string().orEmpty()
                        when (tier) {
                            0 -> parseEmbedHtml(code, body)
                            1 -> parsePostHtml(code, body)
                            else -> parseApiResponseJson(code, body)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Result.failure(AppError.NetworkError(e))
        }
    }

    private fun restricted() = Result.failure<InstagramMediaDetails>(AppError.ContentRestricted(PUBLIC_RESTRICTION))
    private fun unavailable() = Result.failure<InstagramMediaDetails>(AppError.ExtractionFailed(PlatformId.INSTAGRAM))

    internal fun parseEmbedHtml(code: String, html: String) = parseHtml(code, html, true)
    internal fun parsePostHtml(code: String, html: String) = parseHtml(code, html, false)

    private val scripts = Regex("""<script\b([^>]*)>(.*?)</script\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tags = Regex("""<(meta|img|video)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val attributes = Regex("""([\w:-]+)\s*=\s*(["'])(.*?)\2""", RegexOption.DOT_MATCHES_ALL)

    private fun parseHtml(code: String, html: String, embed: Boolean): Result<InstagramMediaDetails> {
        if ("Login &bull; Instagram" in html || "checkpoint_required" in html) return restricted()
        val meta = mutableMapOf<String, String>()
        var embeddedImage: String? = null
        var embeddedVideo: String? = null
        for (tag in tags.findAll(html)) {
            val attrs = attributes.findAll(tag.value).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
            val classes = attrs["class"].orEmpty().split(Regex("""\s+"""))
            when {
                tag.groupValues[1].equals("meta", true) -> attrs["property"]?.let { meta[it] = attrs["content"].orEmpty() }
                "EmbeddedMediaVideo" in classes -> embeddedVideo = videoUrl(attrs["src"])
                "EmbeddedMediaImage" in classes -> embeddedImage = cleanUrl(attrs["src"])
            }
        }
        fun visibleText(className: String) = Regex("""<[^>]+class=["'][^"']*$className[^"']*["'][^>]*>(.*?)</(?:div|a)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)
            ?.replace(Regex("<[^>]*>"), "")?.let(::unescapeHtmlEntities)?.trim()?.ifBlank { null }
        val title = (meta["og:title"] ?: meta["og:description"])?.let(::unescapeHtmlEntities) ?: visibleText("Caption")
        val author = visibleText("Username") ?: title?.takeIf { "on Instagram:" in it }?.substringBefore("on Instagram:")?.trim()
        val sidecarShell = listOf("edge_sidecar_to_children", "carousel_media", "GraphSidecar", "EmbedSidecar").any { it in html }
        for (script in scripts.findAll(html)) {
            val body = script.groupValues[2].trim()
            val start = when {
                body.startsWith("{") || body.startsWith("[") -> 0
                "__additionalDataLoaded" in body || "_sharedData" in body -> body.indexOf('{')
                else -> -1
            }
            if (start < 0) continue
            val json = decode(body.substring(start)) ?: continue
            val media = findMedia(json, code) ?: continue
            if (sidecarShell && media.carouselItems.isEmpty()) continue
            return Result.success(media.copy(title = media.title ?: title, author = media.author ?: author))
        }
        // A sidecar or video shell is not a complete single-photo post.
        if (sidecarShell) return unavailable()
        val video = embeddedVideo ?: if (!embed) videoUrl(meta["og:video:secure_url"] ?: meta["og:video"]) else null
        val image = embeddedImage ?: if (!embed) cleanUrl(meta["og:image"]) else null
        if ("accounts/login" in html && video == null && embeddedImage == null) return restricted()
        val videoShell = "GraphVideo" in html || "EmbeddedMediaVideo" in html || "video_versions" in html ||
            Regex("""["']is_video["']\s*:\s*true""").containsMatchIn(html)
        if (video == null && (image == null || videoShell)) return unavailable()
        return Result.success(InstagramMediaDetails(code, title ?: "Instagram post ($code)", author, image, video, image, video != null,
            isDegradedPreview = video == null))
    }

    private fun decode(text: String): Any? = try {
        JSONTokener(text).nextValue()
    } catch (_: org.json.JSONException) { null }

    internal fun parseApiResponseJson(code: String, json: String): Result<InstagramMediaDetails> {
        val root = decode(json)
        if (root is JSONObject && (root.optString("message") == "login_required" || root.optBoolean("require_login"))) return restricted()
        return findMedia(root, code)?.let { Result.success(it) } ?: unavailable()
    }

    private fun findMedia(value: Any?, code: String, depth: Int = 0): InstagramMediaDetails? {
        if (depth > 48) return null
        if (value is JSONArray) {
            for (i in 0 until value.length()) findMedia(value.opt(i), code, depth + 1)?.let { return it }
        }
        if (value !is JSONObject) return null
        val identifier = (value.opt("shortcode") as? String)?.ifBlank { null }
            ?: (value.opt("code") as? String)?.ifBlank { null }
        if (identifier != null && identifier != code) return null
        val sidecar = value.optJSONObject("edge_sidecar_to_children")
        val children = sidecar?.optJSONArray("edges") ?: value.optJSONArray("carousel_media")
        if (children != null) {
            val slides = mutableListOf<InstagramCarouselItem>()
            val count = sidecar?.optInt("count", children.length()) ?: value.optInt("carousel_media_count", children.length())
            if (children.length() == 0 || count > children.length() ||
                sidecar?.optJSONObject("page_info")?.optBoolean("has_next_page") == true) return null
            for (index in 0 until children.length()) {
                val entry = children.optJSONObject(index) ?: return null
                val node = if (sidecar != null) entry.optJSONObject("node") ?: return null else entry
                slides += slide(node, index) ?: return null
            }
            return details(code, value, slides.first(), slides)
        }
        if (value.has("carousel_media") || sidecar != null || value.optString("__typename") == "GraphSidecar") return null
        slide(value, 0)?.let { return details(code, value, it, emptyList()) }
        // Payload wrappers vary. Never interpret a nested user's avatar or recommended media as the post.
        for (key in value.keys()) {
            if (key in setOf("user", "owner", "recommendations", "related_media")) continue
            val child = value.opt(key)
            val wrapped = if (child is String && key in setOf("gql_data", "data", "shortcode_media", "xdt_shortcode_media") &&
                child.trimStart().startsWith('{')) decode(child) else child
            findMedia(wrapped, code, depth + 1)?.let { return it }
        }
        return null
    }

    private fun details(code: String, obj: JSONObject, item: InstagramCarouselItem, slides: List<InstagramCarouselItem>) =
        InstagramMediaDetails(code, obj.optJSONObject("caption")?.optString("text")
            ?: obj.optJSONObject("edge_media_to_caption")?.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")?.optString("text")
            ?: obj.optString("name").ifBlank { null },
            (obj.optJSONObject("owner") ?: obj.optJSONObject("user"))?.optString("username"), item.imageUrl,
            item.videoUrl, item.imageUrl, item.isVideo, carouselItems = slides)

    private fun slide(obj: JSONObject, index: Int): InstagramCarouselItem? {
        val ldType = obj.optString("@type")
        val video = best(obj.optJSONArray("video_versions"), true) ?: videoUrl(obj.optString("video_url")) ?:
            if (ldType == "VideoObject") videoUrl(obj.optString("contentUrl")) else null
        val image = best(obj.optJSONObject("image_versions2")?.optJSONArray("candidates"), false)
            ?: best(obj.optJSONArray("display_resources"), false) ?: cleanUrl(obj.optString("display_url"))
            ?: if (ldType == "ImageObject") cleanUrl(obj.optString("contentUrl")) else cleanUrl(obj.optString("thumbnailUrl")).takeIf { video != null }
        val needsVideo = obj.optBoolean("is_video") || obj.optInt("media_type") == 2 || obj.has("video_versions") || ldType == "VideoObject"
        if (video == null && (needsVideo || image == null)) return null
        return InstagramCarouselItem(video, image, video != null, index)
    }

    private fun best(array: JSONArray?, video: Boolean): String? {
        if (array == null) return null
        var size = -1L
        var result: String? = null
        for (i in 0 until array.length()) {
            val candidate = array.optJSONObject(i) ?: continue
            val mime = candidate.optString("mime_type").substringBefore(';')
            if (video && mime.isNotBlank() && !mime.equals("video/mp4", ignoreCase = true)) continue
            val raw = candidate.optString("url", candidate.optString("src"))
            val url = (if (video) videoUrl(raw) else cleanUrl(raw)) ?: continue
            val area = candidate.optInt("width", candidate.optInt("config_width")).toLong() *
                candidate.optInt("height", candidate.optInt("config_height"))
            if (area > size) { size = area; result = url }
        }
        return result
    }

    private fun cleanUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // JSONObject has already decoded ordinary JSON. HTML attributes and doubly encoded
        // strings can still contain JSON slash/unicode escapes. Reject unknown backslashes.
        val decoded = Regex("""\\(?:u([0-9a-fA-F]{4})|/)""").replace(raw.trim()) { match ->
            match.groups[1]?.value?.toInt(16)?.toChar()?.toString() ?: "/"
        }
        val url = unescapeHtmlEntities(decoded)
        return url.takeIf { '\\' !in it && it.toHttpUrlOrNull()?.isHttps == true }
    }

    private fun videoUrl(raw: String?): String? = cleanUrl(raw)?.takeUnless {
        it.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() in
            setOf("jpg", "jpeg", "png", "webp", "gif", "webm", "m3u8", "mpd", "mov", "mkv", "avi")
    }

    private fun unescapeHtmlEntities(input: String): String {
        var result = input
        val entityRegex = Regex("""&#([0-9]+);|&#x([0-9a-fA-F]+);|&([a-zA-Z]+);""")
        result = entityRegex.replace(result) { matchResult ->
            val dec = matchResult.groups[1]?.value
            val hex = matchResult.groups[2]?.value
            val named = matchResult.groups[3]?.value
            when {
                dec != null -> {
                    try { dec.toInt().toChar().toString() } catch (e: Exception) { matchResult.value }
                }
                hex != null -> {
                    try { hex.toInt(16).toChar().toString() } catch (e: Exception) { matchResult.value }
                }
                named != null -> {
                    when (named) {
                        "amp" -> "&"
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        "mdash" -> "—"
                        "ndash" -> "–"
                        "nbsp" -> " "
                        else -> matchResult.value
                    }
                }
                else -> matchResult.value
            }
        }
        return result
    }

}
