package com.mediasaver.core.model

/**
 * Technical specification for a single downloadable media stream.
 */
data class StreamInfo(
    val url: String,
    val quality: String? = null,
    val format: String? = null,
    val mimeType: String? = null,
    val isVideoOnly: Boolean = false,
    val isAudioOnly: Boolean = false,
    val fileSize: Long? = null,
    val bitrate: Int? = null,
    val codec: String? = null,
    val mediaIndex: Int? = null,
    val thumbnailUrl: String? = null
) {
    /** True if stream has both audio and video multiplexed natively. */
    val isProgressive: Boolean
        get() = !isVideoOnly && !isAudioOnly

    /** True if this is an adaptive stream requiring muxing. */
    val isAdaptive: Boolean
        get() = isVideoOnly || isAudioOnly
}

/** Select only audio that the Android stream-copy muxer can pair with this video. */
fun StreamInfo.companionAudio(streams: List<StreamInfo>): StreamInfo? {
    val webm = mimeType == "video/webm" || format.equals("webm", true)
    val mp4 = mimeType == "video/mp4" || format.equals("mp4", true)
    val videoCodec = codec.orEmpty().lowercase()
    fun matches(codec: String, prefixes: List<String>) = codec.isBlank() || prefixes.any { codec.startsWith(it) }
    return streams.filter { candidate ->
        val audioCodec = candidate.codec.orEmpty().lowercase()
        val audioFormat = candidate.format.orEmpty().lowercase()
        candidate.isAudioOnly && when {
            webm -> matches(videoCodec, listOf("vp8", "vp9", "vp08", "vp09")) &&
                matches(audioCodec, listOf("opus", "vorbis")) &&
                (candidate.mimeType in setOf("audio/webm", "audio/opus", "audio/vorbis") || audioFormat in setOf("webm", "webma", "opus", "vorbis"))
            mp4 -> matches(videoCodec, listOf("avc", "hevc", "h264", "h265", "hev1", "hvc1", "mp4v")) &&
                matches(audioCodec, listOf("aac", "mp4a")) &&
                (candidate.mimeType in setOf("audio/mp4", "audio/aac") || audioFormat in setOf("mp4", "m4a", "aac"))
            else -> false
        }
    }.maxByOrNull { it.bitrate ?: 0 }
}

fun List<StreamInfo>.isCarousel() = size > 1 &&
    (any { it.mediaIndex != null } || all { it.quality?.startsWith("Slide ") == true })
