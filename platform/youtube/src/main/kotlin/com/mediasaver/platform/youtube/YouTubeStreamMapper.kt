package com.mediasaver.platform.youtube

import com.mediasaver.core.model.StreamInfo
import com.mediasaver.platform.youtube.model.YouTubeDownloadPlan
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps raw NewPipe extraction streams to domain models and executes intelligent stream selection.
 */
@Singleton
class YouTubeStreamMapper @Inject constructor() {

    /**
     * Converts a NewPipe progressive VideoStream (containing audio & video) to domain StreamInfo.
     */
    fun mapProgressiveStream(stream: VideoStream): StreamInfo {
        val formatName = stream.format?.name?.lowercase() ?: "mp4"
        val cleanQuality = normalizeResolution(stream.resolution)

        return StreamInfo(
            url = stream.content,
            quality = cleanQuality,
            format = formatName,
            mimeType = stream.format?.mimeType ?: "video/$formatName",
            isVideoOnly = false,
            isAudioOnly = false,
            fileSize = null,
            bitrate = stream.bitrate.takeIf { it > 0 },
            codec = stream.codec
        )
    }

    /**
     * Converts a NewPipe adaptive VideoStream (video-only, no audio) to domain StreamInfo.
     */
    fun mapAdaptiveVideoStream(stream: VideoStream): StreamInfo {
        val formatName = stream.format?.name?.lowercase() ?: "mp4"
        val cleanQuality = normalizeResolution(stream.resolution)

        return StreamInfo(
            url = stream.content,
            quality = cleanQuality,
            format = formatName,
            mimeType = stream.format?.mimeType ?: "video/$formatName",
            isVideoOnly = true,
            isAudioOnly = false,
            fileSize = null,
            bitrate = stream.bitrate.takeIf { it > 0 },
            codec = stream.codec
        )
    }

    /**
     * Converts a NewPipe AudioStream (audio-only) to domain StreamInfo.
     */
    fun mapAudioStream(stream: AudioStream): StreamInfo {
        val formatName = stream.format?.name?.lowercase() ?: "m4a"
        val bitrateKbps = if (stream.averageBitrate > 0) "${stream.averageBitrate} kbps" else "Audio"

        return StreamInfo(
            url = stream.content,
            quality = bitrateKbps,
            format = formatName,
            mimeType = stream.format?.mimeType ?: "audio/$formatName",
            isVideoOnly = false,
            isAudioOnly = true,
            fileSize = null,
            bitrate = stream.averageBitrate.takeIf { it > 0 } ?: stream.bitrate.takeIf { it > 0 },
            codec = stream.codec
        )
    }

    /**
     * Selects optimal streams according to user preferences and adaptive availability.
     *
     * Rules:
     * 1. If preferred quality is 720p or lower and a progressive stream (audio+video combined) exists,
     *    prefer the single progressive stream to avoid unnecessary multiplexing.
     * 2. For 1080p+ or when high-definition progressive streams are not offered by YouTube,
     *    select the highest/preferred adaptive video stream paired with the highest-bitrate audio stream.
     * 3. Always prefer MP4/AAC compatible containers where possible for wide Android player compatibility.
     */
    fun selectOptimalPlan(
        streams: List<StreamInfo>,
        preferredQuality: String = "720p"
    ): YouTubeDownloadPlan? {
        if (streams.isEmpty()) return null

        val targetHeight = parseHeight(preferredQuality)
        val progressiveStreams = streams.filter { !it.isVideoOnly && !it.isAudioOnly }
        val adaptiveVideoStreams = streams.filter { it.isVideoOnly }
        val audioStreams = streams.filter { it.isAudioOnly }.sortedByDescending { it.bitrate ?: 0 }

        // 1. If preferred quality <= 720p, look for matching progressive stream first
        if (targetHeight <= 720) {
            val matchingProgressive = progressiveStreams.firstOrNull {
                parseHeight(it.quality.orEmpty()) == targetHeight
            }
            if (matchingProgressive != null) {
                return YouTubeDownloadPlan.Progressive(matchingProgressive)
            }
        }

        // 2. Look for adaptive video stream matching preferred quality
        val matchingAdaptiveVideo = findBestVideoStream(adaptiveVideoStreams, targetHeight)
        val bestAudio = audioStreams.firstOrNull()

        if (matchingAdaptiveVideo != null && bestAudio != null) {
            return YouTubeDownloadPlan.Adaptive(
                videoStream = matchingAdaptiveVideo,
                audioStream = bestAudio
            )
        }

        // 3. Fallback: If no adaptive audio is present, use best available progressive stream
        val bestProgressive = progressiveStreams.maxByOrNull { parseHeight(it.quality.orEmpty()) }
        if (bestProgressive != null) {
            return YouTubeDownloadPlan.Progressive(bestProgressive)
        }

        // 4. Last resort: If only adaptive video exists without audio
        if (matchingAdaptiveVideo != null) {
            return YouTubeDownloadPlan.Progressive(matchingAdaptiveVideo)
        }

        return null
    }

    private fun findBestVideoStream(videoStreams: List<StreamInfo>, targetHeight: Int): StreamInfo? {
        if (videoStreams.isEmpty()) return null

        // Try exact match
        val exact = videoStreams.filter { parseHeight(it.quality.orEmpty()) == targetHeight }
        if (exact.isNotEmpty()) {
            return exact.firstOrNull { it.format.equals("mp4", ignoreCase = true) } ?: exact.first()
        }

        // Try highest resolution that does not exceed targetHeight
        val withinLimit = videoStreams
            .filter { parseHeight(it.quality.orEmpty()) <= targetHeight }
            .sortedByDescending { parseHeight(it.quality.orEmpty()) }
        if (withinLimit.isNotEmpty()) {
            return withinLimit.first()
        }

        // Fallback to highest available overall
        return videoStreams.maxByOrNull { parseHeight(it.quality.orEmpty()) }
    }

    /**
     * Parses numeric height from resolution string (e.g. "1080p60" -> 1080, "720p" -> 720).
     */
    fun parseHeight(resolution: String): Int {
        val digits = resolution.takeWhile { it.isDigit() }
        return digits.toIntOrNull() ?: when (resolution.lowercase()) {
            "highest", "max", "4k" -> 2160
            "1440p", "2k" -> 1440
            "1080p", "fhd" -> 1080
            "720p", "hd" -> 720
            "480p", "sd" -> 480
            "360p" -> 360
            "240p" -> 240
            "144p" -> 144
            "lowest", "min" -> 144
            else -> 720
        }
    }

    private fun normalizeResolution(raw: String?): String {
        if (raw.isNullOrBlank()) return "720p"
        val cleaned = raw.trim().lowercase()
        return if (cleaned.endsWith("p") || cleaned.contains("p")) {
            cleaned
        } else {
            "${cleaned}p"
        }
    }
}
