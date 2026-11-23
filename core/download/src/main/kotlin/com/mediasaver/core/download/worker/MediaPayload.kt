package com.mediasaver.core.download.worker

import com.mediasaver.core.common.SecurityUtils
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError

internal enum class MediaPayload(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png"), WEBP("image/webp", "webp"),
    MP4("video/mp4", "mp4"), M4A("audio/mp4", "m4a"), WEBM("video/webm", "webm"),
    WEBM_AUDIO("audio/webm", "webm"), MP3("audio/mpeg", "mp3"), AAC("audio/aac", "aac"), OGG("audio/ogg", "ogg");

    companion object {
        fun verify(header: String?, bytes: ByteArray, stream: StreamInfo): MediaPayload {
            val declared = header?.substringBefore(';')?.trim()?.lowercase()
            val audio = stream.isAudioOnly || stream.mimeType?.startsWith("audio/") == true ||
                stream.format?.lowercase() in setOf("m4a", "mp3", "aac", "ogg")
            val family = when {
                audio -> "audio"
                stream.mimeType?.startsWith("image/") == true || stream.format?.lowercase() in setOf("jpg","jpeg","png","webp") -> "image"
                else -> "video"
            }
            fun prefix(vararg values: Int) = bytes.size >= values.size && values.indices.all { (bytes[it].toInt() and 255) == values[it] }
            fun tag(offset: Int, value: String) = bytes.size >= offset + value.length &&
                String(bytes, offset, value.length, Charsets.US_ASCII) == value
            val kind = when {
                prefix(0xff, 0xd8, 0xff) -> JPEG
                prefix(137,80,78,71,13,10,26,10) -> PNG
                tag(0,"RIFF") && tag(8,"WEBP") -> WEBP
                tag(4,"ftyp") -> if (audio) M4A else MP4
                prefix(0x1a,0x45,0xdf,0xa3) -> if (audio) WEBM_AUDIO else WEBM
                tag(0,"OggS") -> OGG
                bytes.size >= 2 && bytes[0] == (-1).toByte() && (bytes[1].toInt() and 0xf6) == 0xf0 -> AAC
                tag(0,"ID3") || (bytes.size >= 2 && bytes[0] == (-1).toByte() &&
                    (bytes[1].toInt() and 0xe0) == 0xe0 && (bytes[1].toInt() and 6) != 0) -> MP3
                else -> null
            }
            val canonical = when (declared) {
                "audio/m4a", "audio/x-m4a" -> "audio/mp4"
                "audio/mp3" -> "audio/mpeg"
                "image/jpg" -> "image/jpeg"
                "binary/octet-stream" -> "application/octet-stream"
                else -> declared
            }
            val headerMismatch = canonical != null && canonical != "application/octet-stream" &&
                (canonical !in SecurityUtils.ALLOWED_MIME_TYPES || canonical.substringBefore('/') != family)
            if (kind == null || kind.mime.substringBefore('/') != family || headerMismatch) {
                throw AppError.DownloadFailed(null, userMessage = "The response is not the expected media format.")
            }
            return kind
        }
    }
}
