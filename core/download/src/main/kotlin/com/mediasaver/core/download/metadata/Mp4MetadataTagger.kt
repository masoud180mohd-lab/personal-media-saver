package com.mediasaver.core.download.metadata

import com.mediasaver.core.model.AudioMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Validated image bytes suitable for MP4 `covr` atom embedding.
 */
data class CoverArt(
    val bytes: ByteArray,
    val isPng: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CoverArt) return false
        return isPng == other.isPng && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + isPng.hashCode()

    companion object {
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** Detects JPEG or PNG cover art from magic bytes, returning null for other payloads. */
        fun detect(bytes: ByteArray): CoverArt? {
            if (bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            ) {
                return CoverArt(bytes = bytes, isPng = false)
            }
            if (bytes.size >= PNG_MAGIC.size &&
                PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] }
            ) {
                return CoverArt(bytes = bytes, isPng = true)
            }
            return null
        }

        internal fun isWebp(bytes: ByteArray): Boolean =
            bytes.size >= 12 &&
                String(bytes, 0, 4, StandardCharsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, StandardCharsets.US_ASCII) == "WEBP"
    }
}

/**
 * Decoded iTunes/MP4 metadata tags extracted from `moov -> udta -> meta -> ilst`.
 */
data class ParsedAudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null,
    val totalTracks: Int? = null,
    val encoder: String? = null,
    val coverArt: CoverArt? = null
)

/**
 * Pure-Kotlin ISO-BMFF (MP4 / M4A) iTunes metadata & cover art tagger.
 * Preserves media payload bytes untouched and shifts `stco`/`co64` chunk offsets when `moov`
 * precedes `mdat`.
 */
object Mp4MetadataTagger {
    const val MAX_MOOV_BYTES = 16 * 1024 * 1024
    const val DEFAULT_ENCODER = "Personal Media Saver"
    private const val MAX_BOX_COUNT = 100_000
    private const val STREAM_BUFFER_BYTES = 64 * 1024

    private data class Box(
        val offset: Long,
        val size: Long,
        val headerSize: Int,
        val type: String
    ) {
        val end: Long get() = offset + size
        val payloadOffset: Long get() = offset + headerSize
        val payloadSize: Long get() = size - headerSize
    }

    private data class SliceBox(
        val start: Int,
        val size: Int,
        val headerSize: Int,
        val type: String
    ) {
        val end: Int get() = start + size
        val payloadStart: Int get() = start + headerSize
        val payloadSize: Int get() = size - headerSize
    }

    fun tag(
        inputFile: File,
        outputFile: File,
        metadata: AudioMetadata,
        coverArt: CoverArt? = null
    ): Boolean {
        if (!inputFile.isFile || inputFile.length() < 16L) return false
        if (inputFile.absolutePath == outputFile.absolutePath) return false

        val udtaBox = buildUdtaBox(metadata, coverArt) ?: return false

        return try {
            val fileLength = inputFile.length()
            val topBoxes = RandomAccessFile(inputFile, "r").use { raf ->
                parseTopLevelBoxes(raf, fileLength)
            } ?: return false

            if (topBoxes.firstOrNull()?.type != "ftyp") return false
            val moovBox = topBoxes.firstOrNull { it.type == "moov" } ?: return false
            if (moovBox.headerSize != 8 || moovBox.size > MAX_MOOV_BYTES.toLong()) return false

            val oldMoovEnd = moovBox.end
            val oldMoovPayload = ByteArray(moovBox.payloadSize.toInt())
            RandomAccessFile(inputFile, "r").use { raf ->
                raf.seek(moovBox.payloadOffset)
                raf.readFully(oldMoovPayload)
            }

            val newMoov = rebuildMoov(oldMoovPayload, udtaBox) ?: return false
            val delta = newMoov.size.toLong() - moovBox.size
            val hasMdatAfterMoov = topBoxes.any { it.type == "mdat" && it.offset >= oldMoovEnd }
            if (delta != 0L && hasMdatAfterMoov) {
                if (!shiftChunkOffsets(newMoov, oldMoovEnd, delta)) return false
            }

            outputFile.parentFile?.mkdirs()
            RandomAccessFile(inputFile, "r").use { input ->
                outputFile.outputStream().use { out ->
                    copyRange(input, out, 0L, moovBox.offset)
                    out.write(newMoov)
                    copyRange(input, out, oldMoovEnd, fileLength - oldMoovEnd)
                }
            }

            val verified = RandomAccessFile(outputFile, "r").use { raf ->
                parseTopLevelBoxes(raf, outputFile.length())
            }
            if (verified == null ||
                verified.firstOrNull()?.type != "ftyp" ||
                verified.none { it.type == "moov" }
            ) {
                outputFile.delete()
                return false
            }
            true
        } catch (_: Exception) {
            outputFile.delete()
            false
        }
    }

    fun readTags(file: File): ParsedAudioTags {
        if (!file.isFile || file.length() < 16L) return ParsedAudioTags()
        return try {
            val topBoxes = RandomAccessFile(file, "r").use { raf ->
                parseTopLevelBoxes(raf, file.length())
            } ?: return ParsedAudioTags()
            val moovBox = topBoxes.firstOrNull { it.type == "moov" } ?: return ParsedAudioTags()
            if (moovBox.size > MAX_MOOV_BYTES.toLong()) return ParsedAudioTags()

            val moovPayload = ByteArray(moovBox.payloadSize.toInt())
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(moovBox.payloadOffset)
                raf.readFully(moovPayload)
            }

            val moovChildren = parseSliceBoxes(moovPayload, 0, moovPayload.size) ?: return ParsedAudioTags()
            val udta = moovChildren.firstOrNull { it.type == "udta" } ?: return ParsedAudioTags()
            val udtaChildren = parseSliceBoxes(moovPayload, udta.payloadStart, udta.end) ?: return ParsedAudioTags()
            val meta = udtaChildren.firstOrNull { it.type == "meta" } ?: return ParsedAudioTags()
            if (meta.payloadSize < 4) return ParsedAudioTags()

            // `meta` is an ISO FullBox (4-byte version+flags), with fallback if a writer omitted them.
            val metaChildren = parseSliceBoxes(moovPayload, meta.payloadStart + 4, meta.end)
                ?: parseSliceBoxes(moovPayload, meta.payloadStart, meta.end)
                ?: return ParsedAudioTags()
            val ilst = metaChildren.firstOrNull { it.type == "ilst" } ?: return ParsedAudioTags()
            val items = parseSliceBoxes(moovPayload, ilst.payloadStart, ilst.end) ?: return ParsedAudioTags()

            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var trackNumber: Int? = null
            var totalTracks: Int? = null
            var encoder: String? = null
            var coverArt: CoverArt? = null

            for (item in items) {
                val subBoxes = parseSliceBoxes(moovPayload, item.payloadStart, item.end) ?: continue
                val dataBox = subBoxes.firstOrNull { it.type == "data" } ?: continue
                if (dataBox.payloadSize < 8) continue
                val dataType = readUInt32(moovPayload, dataBox.payloadStart).toInt()
                val valueOffset = dataBox.payloadStart + 8
                val valueLen = dataBox.end - valueOffset
                val valueBytes = moovPayload.copyOfRange(valueOffset, valueOffset + valueLen)

                when (item.type) {
                    "\u00A9nam" -> if (dataType == 1) title = String(valueBytes, StandardCharsets.UTF_8)
                    "\u00A9ART" -> if (dataType == 1) artist = String(valueBytes, StandardCharsets.UTF_8)
                    "\u00A9alb" -> if (dataType == 1) album = String(valueBytes, StandardCharsets.UTF_8)
                    "\u00A9too" -> if (dataType == 1) encoder = String(valueBytes, StandardCharsets.UTF_8)
                    "trkn" -> if (valueBytes.size >= 6) {
                        val trk = ((valueBytes[2].toInt() and 0xFF) shl 8) or (valueBytes[3].toInt() and 0xFF)
                        val tot = ((valueBytes[4].toInt() and 0xFF) shl 8) or (valueBytes[5].toInt() and 0xFF)
                        trackNumber = trk.takeIf { it > 0 }
                        totalTracks = tot.takeIf { it > 0 }
                    }
                    "covr" -> if (dataType == 13 || dataType == 14) {
                        coverArt = CoverArt.detect(valueBytes) ?: CoverArt(valueBytes, isPng = dataType == 14)
                    }
                }
            }
            ParsedAudioTags(title, artist, album, trackNumber, totalTracks, encoder, coverArt)
        } catch (_: Exception) {
            ParsedAudioTags()
        }
    }

    private fun buildUdtaBox(metadata: AudioMetadata, coverArt: CoverArt?): ByteArray? {
        val items = ByteArrayOutputStream()
        var hasUserTag = false

        metadata.title?.trim()?.takeIf { it.isNotEmpty() }?.let {
            items.write(tagItem("\u00A9nam", 1, it.toByteArray(StandardCharsets.UTF_8)))
            hasUserTag = true
        }
        metadata.artist?.trim()?.takeIf { it.isNotEmpty() }?.let {
            items.write(tagItem("\u00A9ART", 1, it.toByteArray(StandardCharsets.UTF_8)))
            hasUserTag = true
        }
        metadata.album?.trim()?.takeIf { it.isNotEmpty() }?.let {
            items.write(tagItem("\u00A9alb", 1, it.toByteArray(StandardCharsets.UTF_8)))
            hasUserTag = true
        }
        val track = metadata.trackNumber
        if (track != null && track in 1..65535) {
            val total = (metadata.totalTracks ?: 0).coerceIn(0, 65535)
            val trknBytes = byteArrayOf(
                0, 0,
                ((track ushr 8) and 0xFF).toByte(), (track and 0xFF).toByte(),
                ((total ushr 8) and 0xFF).toByte(), (total and 0xFF).toByte(),
                0, 0
            )
            items.write(tagItem("trkn", 0, trknBytes))
            hasUserTag = true
        }
        if (coverArt != null && coverArt.bytes.isNotEmpty()) {
            val covrType = if (coverArt.isPng) 14 else 13
            items.write(tagItem("covr", covrType, coverArt.bytes))
            hasUserTag = true
        }

        if (!hasUserTag) return null

        items.write(tagItem("\u00A9too", 1, DEFAULT_ENCODER.toByteArray(StandardCharsets.UTF_8)))

        val ilstBox = wrapBox("ilst", items.toByteArray())
        val hdlrPayload = ByteArrayOutputStream().apply {
            writeInt32(0) // version + flags
            writeInt32(0) // pre_defined
            write("mdir".toByteArray(StandardCharsets.ISO_8859_1))
            write("appl".toByteArray(StandardCharsets.ISO_8859_1))
            writeInt32(0)
            writeInt32(0)
            write(0) // empty null-terminated handler name
        }.toByteArray()
        val hdlrBox = wrapBox("hdlr", hdlrPayload)

        val metaPayload = ByteArrayOutputStream().apply {
            writeInt32(0) // FullBox version + flags
            write(hdlrBox)
            write(ilstBox)
        }.toByteArray()
        val metaBox = wrapBox("meta", metaPayload)
        return wrapBox("udta", metaBox)
    }

    private fun tagItem(atomType: String, dataType: Int, value: ByteArray): ByteArray {
        val dataPayload = ByteArrayOutputStream(8 + value.size).apply {
            writeInt32(dataType)
            writeInt32(0) // locale
            write(value)
        }.toByteArray()
        return wrapBox(atomType, wrapBox("data", dataPayload))
    }

    private fun wrapBox(type: String, payload: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(StandardCharsets.ISO_8859_1)
        require(typeBytes.size == 4) { "MP4 box type must be 4 ISO-8859-1 bytes: $type" }
        val total = 8 + payload.size
        return ByteArray(total).apply {
            writeInt32(this, 0, total.toLong())
            System.arraycopy(typeBytes, 0, this, 4, 4)
            System.arraycopy(payload, 0, this, 8, payload.size)
        }
    }

    private fun rebuildMoov(oldMoovPayload: ByteArray, udtaBox: ByteArray): ByteArray? {
        val children = parseSliceBoxes(oldMoovPayload, 0, oldMoovPayload.size) ?: return null
        val rebuiltPayload = ByteArrayOutputStream(oldMoovPayload.size + udtaBox.size)
        for (child in children) {
            if (child.type != "udta") {
                rebuiltPayload.write(oldMoovPayload, child.start, child.size)
            }
        }
        rebuiltPayload.write(udtaBox)
        val payloadBytes = rebuiltPayload.toByteArray()
        val totalSize = 8L + payloadBytes.size
        if (totalSize > (MAX_MOOV_BYTES + 4 * 1024 * 1024).toLong()) return null
        return wrapBox("moov", payloadBytes)
    }

    private fun shiftChunkOffsets(moovBytes: ByteArray, oldMoovEnd: Long, delta: Long): Boolean {
        val moovChildren = parseSliceBoxes(moovBytes, 8, moovBytes.size) ?: return false
        for (trak in moovChildren) {
            if (trak.type != "trak") continue
            val mdiaBoxes = parseSliceBoxes(moovBytes, trak.payloadStart, trak.end) ?: return false
            for (mdia in mdiaBoxes) {
                if (mdia.type != "mdia") continue
                val minfBoxes = parseSliceBoxes(moovBytes, mdia.payloadStart, mdia.end) ?: return false
                for (minf in minfBoxes) {
                    if (minf.type != "minf") continue
                    val stblBoxes = parseSliceBoxes(moovBytes, minf.payloadStart, minf.end) ?: return false
                    for (stbl in stblBoxes) {
                        if (stbl.type != "stbl") continue
                        val leafBoxes = parseSliceBoxes(moovBytes, stbl.payloadStart, stbl.end) ?: return false
                        for (leaf in leafBoxes) {
                            when (leaf.type) {
                                "stco" -> if (!patchStco(moovBytes, leaf, oldMoovEnd, delta)) return false
                                "co64" -> if (!patchCo64(moovBytes, leaf, oldMoovEnd, delta)) return false
                            }
                        }
                    }
                }
            }
        }
        return true
    }

    private fun patchStco(buf: ByteArray, stco: SliceBox, oldMoovEnd: Long, delta: Long): Boolean {
        if (stco.payloadSize < 8) return false
        val countLong = readUInt32(buf, stco.payloadStart + 4)
        if (countLong > Int.MAX_VALUE) return false
        val count = countLong.toInt()
        if (8L + count.toLong() * 4L > stco.payloadSize.toLong()) return false

        var pos = stco.payloadStart + 8
        repeat(count) {
            val orig = readUInt32(buf, pos)
            if (orig >= oldMoovEnd) {
                val updated = orig + delta
                if (updated < 0L || updated > 0xFFFFFFFFL) return false
                writeInt32(buf, pos, updated)
            }
            pos += 4
        }
        return true
    }

    private fun patchCo64(buf: ByteArray, co64: SliceBox, oldMoovEnd: Long, delta: Long): Boolean {
        if (co64.payloadSize < 8) return false
        val countLong = readUInt32(buf, co64.payloadStart + 4)
        if (countLong > Int.MAX_VALUE) return false
        val count = countLong.toInt()
        if (8L + count.toLong() * 8L > co64.payloadSize.toLong()) return false

        var pos = co64.payloadStart + 8
        repeat(count) {
            val orig = readInt64(buf, pos)
            if (orig < 0L) return false
            if (orig >= oldMoovEnd) {
                val updated = orig + delta
                if (updated < 0L) return false
                writeInt64(buf, pos, updated)
            }
            pos += 8
        }
        return true
    }

    private fun parseTopLevelBoxes(raf: RandomAccessFile, fileLength: Long): List<Box>? {
        val boxes = mutableListOf<Box>()
        val header = ByteArray(8)
        val ext = ByteArray(8)
        var offset = 0L

        while (offset < fileLength) {
            if (boxes.size >= MAX_BOX_COUNT) return null
            if (fileLength - offset < 8L) return null
            raf.seek(offset)
            raf.readFully(header)

            val size32 = readUInt32(header, 0)
            val type = String(header, 4, 4, StandardCharsets.ISO_8859_1)
            val headerSize: Int
            val size: Long

            when (size32) {
                0L -> {
                    headerSize = 8
                    size = fileLength - offset
                }
                1L -> {
                    if (fileLength - offset < 16L) return null
                    raf.readFully(ext)
                    val large = readInt64(ext, 0)
                    if (large < 16L) return null
                    headerSize = 16
                    size = large
                }
                else -> {
                    if (size32 < 8L) return null
                    headerSize = 8
                    size = size32
                }
            }

            if (size > fileLength - offset) return null
            boxes.add(Box(offset, size, headerSize, type))
            offset += size
        }
        return if (offset == fileLength) boxes else null
    }

    private fun parseSliceBoxes(buf: ByteArray, start: Int, end: Int): List<SliceBox>? {
        if (start < 0 || end > buf.size || start > end) return null
        val boxes = mutableListOf<SliceBox>()
        var cursor = start
        while (cursor < end) {
            if (boxes.size >= MAX_BOX_COUNT) return null
            if (end - cursor < 8) return null
            val size32 = readUInt32(buf, cursor)
            val type = String(buf, cursor + 4, 4, StandardCharsets.ISO_8859_1)
            val headerSize: Int
            val sizeLong: Long
            when (size32) {
                0L -> {
                    headerSize = 8
                    sizeLong = (end - cursor).toLong()
                }
                1L -> {
                    if (end - cursor < 16) return null
                    val large = readInt64(buf, cursor + 8)
                    if (large < 16L) return null
                    headerSize = 16
                    sizeLong = large
                }
                else -> {
                    if (size32 < 8L) return null
                    headerSize = 8
                    sizeLong = size32
                }
            }
            if (sizeLong > (end - cursor).toLong()) return null
            val size = sizeLong.toInt()
            boxes.add(SliceBox(cursor, size, headerSize, type))
            cursor += size
        }
        return if (cursor == end) boxes else null
    }

    private fun copyRange(input: RandomAccessFile, out: java.io.OutputStream, start: Long, length: Long) {
        if (length <= 0L) return
        input.seek(start)
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var remaining = length
        while (remaining > 0L) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = input.read(buffer, 0, toRead)
            check(read > 0) { "Unexpected EOF while copying MP4 bytes" }
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun ByteArrayOutputStream.writeInt32(v: Int) {
        write((v ushr 24) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 8) or
            (buf[offset + 3].toLong() and 0xFFL)

    private fun writeInt32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFFL).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFFL).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFFL).toByte()
        buf[offset + 3] = (value and 0xFFL).toByte()
    }

    private fun readInt64(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFFL) shl 56) or
            ((buf[offset + 1].toLong() and 0xFFL) shl 48) or
            ((buf[offset + 2].toLong() and 0xFFL) shl 40) or
            ((buf[offset + 3].toLong() and 0xFFL) shl 32) or
            ((buf[offset + 4].toLong() and 0xFFL) shl 24) or
            ((buf[offset + 5].toLong() and 0xFFL) shl 16) or
            ((buf[offset + 6].toLong() and 0xFFL) shl 8) or
            (buf[offset + 7].toLong() and 0xFFL)

    private fun writeInt64(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 56) and 0xFFL).toByte()
        buf[offset + 1] = ((value ushr 48) and 0xFFL).toByte()
        buf[offset + 2] = ((value ushr 40) and 0xFFL).toByte()
        buf[offset + 3] = ((value ushr 32) and 0xFFL).toByte()
        buf[offset + 4] = ((value ushr 24) and 0xFFL).toByte()
        buf[offset + 5] = ((value ushr 16) and 0xFFL).toByte()
        buf[offset + 6] = ((value ushr 8) and 0xFFL).toByte()
        buf[offset + 7] = (value and 0xFFL).toByte()
    }
}
