package com.mediasaver.platform.twitter

import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class TwitterMedia(
    val title: String,
    val author: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int?,
    val streams: List<StreamInfo>
)

@Singleton
class TwitterExtractor @Inject constructor(private val okHttpClient: OkHttpClient) {
    private val client = okHttpClient.twitterClient(setOf(SYNDICATION_HOST))

    suspend fun extract(tweetId: String): Result<TwitterMedia> {
        if (!TwitterUrlParser.validId(tweetId)) return Result.failure(failed())
        val token = TwitterUrlParser.computeSyndicationToken(tweetId)
        val url = "https://$SYNDICATION_HOST/tweet-result?id=$tweetId&lang=en&token=$token"

        return try {
            Result.success(client.readTwitter(syndicationRequest(url).build()) { response ->
                when (response.code) {
                    404, 410 -> throw AppError.MediaNotFound(TwitterUrlParser.buildCanonicalStatusUrl(tweetId))
                    429, in 500..599 -> throw AppError.NetworkError(IOException("HTTP ${response.code}"))
                }
                if (!response.isSuccessful) throw failed()
                val body = response.body ?: throw failed()
                if (body.source().request(2L * 1024 * 1024 + 1)) throw failed()
                val tweet = JsonReader(body.string()).root()
                parse(tweet, tweetId)
            })
        } catch (e: AppError) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(AppError.NetworkError(e))
        } catch (e: IllegalArgumentException) {
            Result.failure(failed())
        }
    }

    private fun parse(tweet: Map<*, *>, id: String): TwitterMedia {
        val user = tweet.obj("user")
        if (tweet["__typename"]?.let { it != "Tweet" } == true ||
            tweet["id_str"]?.let { it != id } == true || tweet["tombstone"] != null ||
            user?.get("protected") == true || tweet["protected"] == true
        ) throw failed()

        val details = tweet.objects("mediaDetails")
        val video = tweet.obj("video")
        val attachments = details.filter { it["type"] == "video" || it["type"] == "animated_gif" }
        val poster = mediaUrl(video?.get("poster"))
            ?: details.firstNotNullOfOrNull { mediaUrl(it["media_url_https"]) }
        val videos = mutableListOf<Pair<Int, StreamInfo>>()

        fun addVariants(info: Map<*, *>?, gif: Boolean, thumbnail: String?) {
            for (variant in info?.objects("variants").orEmpty()) {
                val src = mediaUrl(variant["url"]) ?: mediaUrl(variant["src"]) ?: continue
                val link = TwitterUrlParser.https(src) ?: continue
                val type = variant["content_type"] ?: variant["type"]
                if (type != "video/mp4" && !(link.host == "video.twimg.com" &&
                    link.encodedPath.endsWith(".mp4", true))) continue

                val size = SIZE.find(link.encodedPath)
                val height = size?.let {
                    minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt())
                } ?: 0
                val bitrate = variant.nonnegative("bitrate")?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                val label = when {
                    gif -> "GIF"
                    height > 0 -> "${height}p"
                    (bitrate ?: 0) >= 1_000_000 -> "HD"
                    else -> "SD"
                }
                videos += height to StreamInfo(
                    url = src, quality = "$label (MP4)", format = "mp4",
                    mimeType = "video/mp4", bitrate = bitrate, thumbnailUrl = thumbnail
                )
            }
        }

        for (attachment in attachments) {
            addVariants(
                attachment.obj("video_info"), attachment["type"] == "animated_gif",
                mediaUrl(attachment["media_url_https"]) ?: poster
            )
        }
        addVariants(
            video,
            video?.get("type") == "animated_gif" || attachments.firstOrNull()?.get("type") == "animated_gif",
            poster
        )

        val streams = if (videos.isNotEmpty()) {
            videos.sortedWith(
                compareByDescending<Pair<Int, StreamInfo>> { it.second.bitrate ?: -1 }
                    .thenByDescending { it.first }
            ).distinctBy { it.second.url }.map { it.second }
        } else {
            details.filter { it["type"] == "photo" }.mapNotNull { mediaUrl(it["media_url_https"]) }
                .distinct().mapIndexed { index, url ->
                    StreamInfo(
                        url = url, quality = "Slide ${index + 1}", format = "jpg",
                        mimeType = "image/jpeg", mediaIndex = index, thumbnailUrl = url
                    )
                }
        }
        if (streams.isEmpty()) throw failed()

        val millis = video?.nonnegative("durationMs") ?: video?.nonnegative("duration_millis")
            ?: attachments.firstNotNullOfOrNull { it.obj("video_info")?.nonnegative("duration_millis") }
            ?: tweet.nonnegative("duration_millis")
        val title = tweet.text("text")?.replace(WHITESPACE, " ") ?: "X post"
        val author = user?.text("name") ?: user?.text("screen_name")
        return TwitterMedia(
            title, author, poster ?: streams.first().thumbnailUrl,
            millis?.div(1000)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(), streams
        )
    }

    private companion object {
        val MEDIA_HOSTS = setOf("video.twimg.com", "pbs.twimg.com", "twimg.com")
        val SIZE = Regex("""/vid/(?:avc1/)?(\d{1,5})x(\d{1,5})/""")
        val WHITESPACE = Regex("""\s+""")
        fun failed() = AppError.ExtractionFailed(PlatformId.TWITTER)
        fun mediaUrl(value: Any?) = (value as? String)?.let(TwitterUrlParser::https)
            ?.takeIf { it.host in MEDIA_HOSTS }?.toString()
        fun Map<*, *>.obj(key: String) = this[key] as? Map<*, *>
        fun Map<*, *>.objects(key: String) = (this[key] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
        fun Map<*, *>.text(key: String) = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        fun Map<*, *>.nonnegative(key: String) = this[key]?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
    }
}

// Structural parsing keeps quoted posts, captions and unrelated URLs out of the media list.
private class JsonReader(private val input: String) {
    private var pos = 0
    private var nodes = 0

    fun root(): Map<*, *> {
        val root = value(0) as? Map<*, *> ?: invalid()
        space()
        if (pos != input.length) invalid()
        return root
    }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid syndication JSON")

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
