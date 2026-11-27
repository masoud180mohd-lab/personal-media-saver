package com.mediasaver.platform.pinterest

import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import com.mediasaver.core.network.interceptor.PlatformWebDomain
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

data class PinterestMedia(
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val streams: List<StreamInfo>
)

@Singleton
class PinterestExtractor @Inject constructor(private val okHttpClient: OkHttpClient) {
    private val http = okHttpClient.pinClient()

    suspend fun extract(pinId: String): Result<PinterestMedia> {
        if (!PinterestUrlParser.validId(pinId)) return Result.failure(failed())
        val url = PinterestUrlParser.buildCanonicalPinUrl(pinId)
        return try {
            Result.success(http.readPin(pinRequest(url).build()) { response ->
                when (response.code) {
                    404, 410 -> throw AppError.MediaNotFound(url)
                    429, in 500..599 -> throw AppError.NetworkError(IOException("HTTP ${response.code}"))
                }
                if (!response.isSuccessful ||
                    PinterestUrlParser.extractPinId(response.request.url.toString()) != pinId
                ) throw failed()

                val body = response.body ?: throw failed()
                if (body.source().request(4L * 1024 * 1024 + 1)) throw failed()
                PinPage(pinId).parse(body.string())
            })
        } catch (e: IOException) {
            Result.failure(AppError.NetworkError(e))
        } catch (e: AppError) {
            Result.failure(e)
        }
    }
}

private fun failed() = AppError.ExtractionFailed(PlatformId.PINTEREST)
private typealias Obj = Map<*, *>
private fun Obj.text(key: String) = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

private class PinPage(private val id: String) {
    private var title: String? = null
    private var author: String? = null
    private var seconds: Int? = null
    private var thumbnail: String? = null

    private data class Video(val url: String, val height: Int, val label: String)
    private data class Photo(val url: String, val format: String, val original: Boolean, val width: Int)

    private val videos = mutableListOf<Video>()
    private val photos = mutableListOf<Photo>()
    private val meta = mutableMapOf<String, MutableList<String>>()

    fun parse(html: String): PinterestMedia {
        var pos = 0
        while (true) {
            val start = ELEMENT.find(html, pos) ?: break
            pos = start.range.last + 1
            if (start.value == "<!--") {
                val close = html.indexOf("-->", pos)
                if (close < 0) break
                pos = close + 3
                continue
            }

            val from = pos
            var quote = '\u0000'
            while (pos < html.length) {
                val c = html[pos++]
                if (quote != '\u0000') {
                    if (c == quote) quote = '\u0000'
                } else if (c == '"' || c == '\'') quote = c
                else if (c == '>') break
            }
            val attributes = ATTR.findAll(html.substring(from, pos)).associate {
                val value = it.groups[2] ?: it.groups[3] ?: it.groups[4]
                it.groupValues[1].lowercase() to entities(value!!.value)
            }
            if (start.groupValues[1].equals("meta", true)) {
                val key = attributes["property"] ?: attributes["name"] ?: continue
                val content = attributes["content"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                meta.getOrPut(key.lowercase()) { mutableListOf() }.add(content)
            } else {
                val end = html.indexOf("</script", pos, ignoreCase = true)
                if (end < 0) break
                script(html.substring(pos, end), attributes)
                pos = end + 8
            }
        }

        for (key in listOf("og:video:secure_url", "og:video:url", "og:video")) {
            meta[key].orEmpty().forEach { video(it) }
        }
        for (key in listOf("og:image:secure_url", "og:image", "twitter:image")) {
            meta[key].orEmpty().forEach { image(it) }
        }
        if (seconds == null) seconds = duration(meta["og:video:duration"]?.firstOrNull())

        val best = photos.maxWithOrNull(compareBy<Photo> { it.original }.thenBy { it.width })
        val cover = best?.url ?: thumbnail
        val streams = if (videos.isNotEmpty()) {
            videos.sortedByDescending { it.height }.distinctBy { it.url }.map {
                StreamInfo(
                    url = it.url, quality = it.label, format = "mp4",
                    mimeType = "video/mp4", thumbnailUrl = cover
                )
            }
        } else {
            val p = best ?: throw failed()
            listOf(
                StreamInfo(
                    url = p.url, quality = "Original (${p.format.uppercase()})",
                    format = p.format, mimeType = if (p.format == "png") "image/png" else "image/jpeg",
                    thumbnailUrl = p.url
                )
            )
        }
        return PinterestMedia(
            title ?: meta["og:title"]?.firstOrNull() ?: "Pinterest pin",
            author ?: meta["author"]?.firstOrNull(), cover, seconds, streams
        )
    }

    private fun script(raw: String, attrs: Map<String, String>) {
        val ld = attrs["type"].orEmpty().substringBefore(';').trim().equals("application/ld+json", true)
        var json = raw.trim()
        if (ASSIGN.containsMatchIn(json)) {
            json = json.substringAfter('=').trim().removeSuffix(";").trimEnd()
        }
        if (!json.startsWith('{') && !json.startsWith('[')) return
        val root = try {
            JsonReader(json).root()
        } catch (_: IllegalArgumentException) {
            return
        }

        fun locate(value: Any?, direct: Boolean) {
            when (value) {
                is List<*> -> value.forEach { locate(it, direct) }
                is Map<*, *> -> {
                    val linkedId = value.text("@id")?.let(PinterestUrlParser::extractPinId)
                        ?: value.text("url")?.let(PinterestUrlParser::extractPinId)
                    if (linkedId != null && linkedId != id) return
                    val objectId = value["id"]?.toString()
                    if (ld && value.text("@type") in setOf(
                            "Person", "Organization", "WebSite", "BreadcrumbList", "ItemList"
                        )
                    ) return

                    if (objectId == id || (direct && (objectId == null || ld))) readPin(value)
                    if (ld) {
                        locate(value["@graph"], true)
                        locate(value["mainEntity"], true)
                    } else if (objectId == null) {
                        for ((k, v) in value) {
                            if (k == id && v is Map<*, *> && (v["id"] == null || v["id"].toString() == id)) {
                                readPin(v)
                            } else if (v is Map<*, *> || v is List<*>) {
                                locate(v, k == "pin")
                            }
                        }
                    }
                }
            }
        }
        locate(root, true)
    }

    private fun readPin(pin: Obj) {
        if (title == null) {
            title = pin.text("grid_title") ?: pin.text("title") ?: pin.text("headline") ?: pin.text("name")
        }
        if (author == null) {
            author = listOf("pinner", "creator", "author").firstNotNullOfOrNull {
                when (val person = pin[it]) {
                    is String -> person.trim().takeIf(String::isNotEmpty)
                    is Map<*, *> -> person.text("full_name") ?: person.text("name")
                        ?: listOfNotNull(person.text("first_name"), person.text("last_name"))
                            .joinToString(" ").ifBlank { person.text("username") }
                    else -> null
                }
            }
        }
        media(pin)
    }

    private fun media(value: Any?, key: String = "") {
        when (value) {
            is List<*> -> value.forEach { media(it, key) }
            is Map<*, *> -> {
                val variant = key.startsWith("V_", true)
                val height = value["height"]?.toString()?.toIntOrNull()?.takeIf { it > 0 }
                val width = value["width"]?.toString()?.toIntOrNull() ?: 0
                if (seconds == null) {
                    seconds = duration(value["durationSeconds"] ?: value["duration_seconds"])
                        ?: duration(value["durationMs"] ?: value["duration_ms"], millis = true)
                        ?: duration(value["duration"], millis = variant || key == "videos")
                }
                val src = value.text("contentUrl") ?: value.text("content_url") ?: value.text("url")
                video(src, key, height)
                image(src, key, width)

                for ((k, v) in value) {
                    if (k is String && (k in MEDIA_KEYS || k.startsWith("V_") || key == "images" ||
                        (k == "data" && key == "blocks"))) {
                        media(v, k)
                    }
                }
            }
            is String -> {
                video(value, key)
                image(value, key)
            }
        }
    }

    private fun video(raw: String?, key: String = "", height: Int? = null) {
        val u = raw?.let(PinterestUrlParser::https) ?: return
        if (!CDN.accepts(u.host) || !u.encodedPath.endsWith(".mp4", true)) return
        val h = height ?: QUALITY.matchEntire(key)?.groupValues?.get(1)?.toIntOrNull()
        val rank = h ?: when (key.uppercase()) {
            "V_HD" -> 720
            "V_SD" -> 480
            else -> 0
        }
        val label = when {
            h != null -> "${h}p (MP4)"
            key.equals("V_HD", true) -> "HD (MP4)"
            key.equals("V_SD", true) -> "SD (MP4)"
            else -> "MP4"
        }
        videos.add(Video(u.toString(), rank, label))
    }

    private fun image(raw: String?, key: String = "", width: Int = 0) {
        val u = raw?.let(PinterestUrlParser::https) ?: return
        if (!CDN.accepts(u.host)) return
        val ext = u.encodedPath.substringAfterLast('.').lowercase()
        if (ext !in setOf("jpg", "jpeg", "png", "webp", "avif")) return
        if (thumbnail == null) thumbnail = u.toString()
        if (u.host != "i.pinimg.com" || ext !in setOf("jpg", "jpeg", "png")) return
        val folder = u.pathSegments.firstOrNull().orEmpty()
        val size = folder.substringBefore('x').toIntOrNull() ?: 0
        photos.add(
            Photo(
                u.toString(), if (ext == "jpeg") "jpg" else ext,
                folder == "originals" || key == "orig", maxOf(width, size)
            )
        )
    }

    private fun duration(value: Any?, millis: Boolean = false): Int? {
        val raw = value?.toString() ?: return null
        if (raw.length > 64) return null
        val n = raw.toDoubleOrNull()?.div(if (millis) 1000 else 1)
            ?: runCatching { Duration.parse(raw).seconds.toDouble() }.getOrNull() ?: return null
        return n.takeIf { it in 0.0..Int.MAX_VALUE.toDouble() }?.toInt()
    }

    private companion object {
        val ELEMENT = Regex("""<!--|<(meta|script)\b""", RegexOption.IGNORE_CASE)
        val ATTR = Regex("""(?<!\s)\s++([\w:-]++)\s*+=\s*+(?:"([^"]*+)"|'([^']*+)'|([^\s>"']++))""")
        val MEDIA_KEYS = setOf(
            "thumbnailUrl", "thumbnail_url", "poster", "image", "images",
            "video", "videos", "video_list", "story_pin_data", "pages", "blocks"
        )
        val ASSIGN = Regex("""^(?:window\.)?__PWS_INITIAL_PROPS__\s*=""")
        val QUALITY = Regex("""V_(\d{2,4})P""", RegexOption.IGNORE_CASE)
        val CDN = PlatformWebDomain("pinimg.com")
        val ENTITY = Regex("""&(amp|quot|apos|lt|gt|#\d{1,7}|#[xX][\da-fA-F]{1,6});""")

        fun entities(s: String) = ENTITY.replace(s) {
            when (val name = it.groupValues[1]) {
                "amp" -> "&"
                "quot" -> "\""
                "apos" -> "'"
                "lt" -> "<"
                "gt" -> ">"
                else -> {
                    val n = if (name.startsWith("#x", true)) name.drop(2).toInt(16) else name.drop(1).toInt()
                    if (Character.isValidCodePoint(n)) String(Character.toChars(n)) else it.value
                }
            }
        }
    }
}

private class JsonReader(private val input: String) {
    private var pos = 0
    private var nodes = 0

    fun root(): Any? {
        val root = value(0)
        space()
        if (pos != input.length) invalid()
        return root
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid pin JSON")

    private fun space() {
        while (pos < input.length && input[pos] in " \t\r\n") pos++
    }

    private fun take(c: Char): Boolean {
        if (input.getOrNull(pos) != c) return false
        pos++
        return true
    }

    private fun value(depth: Int): Any? {
        if (depth > 48 || ++nodes > 100_000) invalid()
        space()
        return when (input.getOrNull(pos)) {
            '{' -> {
                pos++
                val map = mutableMapOf<String, Any?>()
                space()
                if (take('}')) return map
                do {
                    space()
                    val key = string()
                    space()
                    if (!take(':') || map.containsKey(key)) invalid()
                    map[key] = value(depth + 1)
                    space()
                } while (take(','))
                if (!take('}')) invalid()
                map
            }
            '[' -> {
                pos++
                val list = mutableListOf<Any?>()
                space()
                if (take(']')) return list
                do {
                    list.add(value(depth + 1))
                    space()
                } while (take(','))
                if (!take(']')) invalid()
                list
            }
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> {
                val number = NUMBER.matchAt(input, pos) ?: invalid()
                pos = number.range.last + 1
                number.value.toLongOrNull() ?: number.value.toDoubleOrNull() ?: invalid()
            }
        }
    }

    private fun literal(word: String, result: Boolean?): Boolean? {
        if (!input.startsWith(word, pos)) invalid()
        pos += word.length
        return result
    }

    private fun string(): String {
        if (!take('"')) invalid()
        return buildString {
            while (pos < input.length) {
                val c = input[pos++]
                when {
                    c == '"' -> return@buildString
                    c < ' ' -> invalid()
                    c != '\\' -> append(c)
                    else -> append(when (val escape = input.getOrNull(pos++)) {
                        '"', '\\', '/' -> escape!!
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            var unit = 0
                            repeat(4) {
                                val digit = input.getOrNull(pos++)?.digitToIntOrNull(16) ?: invalid()
                                unit = unit * 16 + digit
                            }
                            unit.toChar()
                        }
                        else -> invalid()
                    })
                }
            }
            invalid()
        }
    }

    private companion object {
        val NUMBER = Regex("""-?(?:0|[1-9][0-9]*+)(?:\.[0-9]++)?(?:[eE][+-]?[0-9]++)?""")
    }
}
