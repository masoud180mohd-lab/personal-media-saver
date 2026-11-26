package com.mediasaver.platform.facebook

import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class FacebookVideo(
    val title: String?,
    val author: String?,
    val thumbnailUrl: String?,
    val hdUrl: String?,
    val sdUrl: String?
)

@Singleton
class FacebookExtractor @Inject constructor(private val okHttpClient: OkHttpClient) {
    private val client = okHttpClient.facebookPublicClient()

    suspend fun extract(videoId: String): Result<FacebookVideo> {
        if (!FacebookUrlParser.validId(videoId)) return Result.failure(failed())
        val request = facebookRequest(FacebookUrlParser.buildCanonicalWatchUrl(videoId)).build()

        return try {
            Result.success(client.readFacebook(request) { response ->
                if (response.code == 404 || response.code == 410) {
                    throw AppError.MediaNotFound(videoId)
                }
                if (response.code == 429 || response.code in 500..599) {
                    throw AppError.NetworkError(IOException("HTTP ${response.code}"))
                }
                if (!response.isSuccessful ||
                    FacebookUrlParser.extractVideoId(response.request.url.toString()) != videoId
                ) throw failed()

                val body = response.body ?: throw failed()
                if (body.source().request(MAX_HTML_BYTES + 1)) throw failed()
                parse(body.string())
            })
        } catch (e: AppError) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(AppError.NetworkError(e))
        }
    }

    private fun parse(html: String): FacebookVideo {
        val meta = mutableMapOf<String, MutableList<String>>()
        for (tag in META.findAll(html)) {
            val attrs = ATTRIBUTE.findAll(tag.value).associate {
                val value = it.groups[2] ?: it.groups[3] ?: it.groups[4]
                it.groupValues[1].lowercase() to entities(value!!.value.trim())
            }
            val key = attrs["property"] ?: attrs["name"] ?: continue
            val value = attrs["content"]?.takeIf { it.isNotBlank() } ?: continue
            meta.getOrPut(key.lowercase()) { mutableListOf() }.add(value)
        }

        val pageTitle = TITLE.find(html)?.groupValues?.get(1)?.let(::entities)?.trim()
        val title = meta["og:title"]?.firstOrNull() ?: pageTitle
        val visible = entities(TAG.replace(SCRIPT_STYLE.replace(html, " "), " "))
            .replace(SPACE, " ")

        if (WALL.containsMatchIn(visible) ||
            listOfNotNull(title, pageTitle).any { LOGIN_TITLE.containsMatchIn(it) } ||
            LOGIN_FORM.containsMatchIn(html) || PRIVATE_FLAG.containsMatchIn(html)
        ) throw failed()

        fun stream(keys: List<Regex>): String? = keys.asSequence()
            .flatMap { it.findAll(html) }
            .mapNotNull { FacebookUrlParser.mediaUrl(decodeJson(it.groupValues[1])) }
            .firstOrNull()

        fun metaUrl(key: String) =
            meta[key].orEmpty().firstNotNullOfOrNull(FacebookUrlParser::mediaUrl)

        val hd = stream(HD)
        val sd = stream(SD) ?: metaUrl("og:video:secure_url") ?: metaUrl("og:video")
        if (hd == null && sd == null) throw failed()

        val author = meta["author"]?.firstOrNull() ?: meta["article:author"]?.firstOrNull()
            ?: OWNER.find(html)?.groupValues?.get(1)?.let(::decodeJson)

        return FacebookVideo(title, author, metaUrl("og:image"), hd, sd)
    }

    private companion object {
        const val MAX_HTML_BYTES = 4L * 1024 * 1024
        fun failed() = AppError.ExtractionFailed(PlatformId.FACEBOOK)

        val META = Regex("""<meta\b(?:[^>"']++|"[^"]*+"|'[^']*+')*+>""", RegexOption.IGNORE_CASE)
        val ATTRIBUTE = Regex("""\s+([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
        val TITLE = Regex("""(?is)<title\b[^>]*>(.*?)</title\s*>""")
        val SCRIPT_STYLE = Regex("""(?is)<(script|style)\b[^>]*>.*?</\1\s*>""")
        val TAG = Regex("""<[^>]*>""")
        val SPACE = Regex("""\s+""")
        val LOGIN_TITLE = Regex("""(?i)^\s*(?:facebook\s*[|–-]\s*)?(?:log\s*in(?:to)?|sign\s*in)\b""")
        val LOGIN_FORM = Regex("""(?i)<form\b[^>]*\s(?:id|name)\s*=\s*["']login_form["']""")
        val WALL = Regex(
            """(?i)(?:you (?:must|need to) log in|log in to (?:continue|watch this video|see this (?:post|video))|""" +
                """this (?:video|post) is private|this content (?:is not|isn['’]t) available)"""
        )
        val PRIVATE_FLAG = Regex(""""(?:is_private|is_login_required)"\s*:\s*true\b""")

        const val JSON_VALUE = """"((?:[^"\\]++|\\.)*+)""""
        fun fields(vararg names: String) = names.map {
            Regex("""(?:^|[,{])\s*(?:"$it"|$it)\s*:\s*$JSON_VALUE""")
        }

        val HD = fields("browser_native_hd_url", "playable_url_quality_hd", "hd_src_no_ratelimit", "hd_src")
        val SD = fields("browser_native_sd_url", "playable_url", "sd_src_no_ratelimit", "sd_src")
        val OWNER = Regex(""""(?:owner|actor|author)"\s*:\s*\{[^{}]{0,1024}?"name"\s*:\s*$JSON_VALUE""")
        val ESCAPE = Regex("""\\(?:u([0-9a-fA-F]{4})|(["\\/]))""")
        val ENTITY = Regex("""&(?:amp|quot|apos|lt|gt|#\d{1,7}|#[xX][a-fA-F0-9]{1,6});""")

        fun decodeJson(value: String): String = entities(ESCAPE.replace(value) {
            it.groups[1]?.value?.toInt(16)?.toChar()?.toString() ?: it.groupValues[2]
        })

        fun entities(value: String): String = ENTITY.replace(value) {
            when (val name = it.value.substring(1, it.value.length - 1)) {
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                else -> {
                    val n = if (name.startsWith("#x", true)) {
                        name.drop(2).toInt(16)
                    } else {
                        name.drop(1).toInt()
                    }
                    if (Character.isValidCodePoint(n)) String(Character.toChars(n)) else it.value
                }
            }
        }
    }
}
