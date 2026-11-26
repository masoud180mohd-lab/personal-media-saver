package com.mediasaver.core.download.metadata

import com.mediasaver.core.model.AudioMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

class Mp4MetadataTaggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `tags fast-start M4A with text, track number, and JPEG cover while shifting stco and co64 offsets`() {
        val mediaPayload = "M4A_AUDIO_PAYLOAD_1234567890".toByteArray(StandardCharsets.US_ASCII)
        val inputFile = tempFolder.newFile("fast_start_input.m4a")
        writeSyntheticMp4(
            dest = inputFile,
            moovBeforeMdat = true,
            mediaPayload = mediaPayload,
            includeCo64 = true,
            includeDummyUdta = false
        )

        val jpegCover = CoverArt(
            bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x11, 0x22, 0x33),
            isPng = false
        )
        val outputFile = File(tempFolder.root, "fast_start_tagged.m4a")
        val metadata = AudioMetadata(
            title = "Darsa 3: Tawheed",
            artist = "Sheikh Ahmad",
            album = "Kitabu cha Tawheed",
            trackNumber = 3,
            totalTracks = 12
        )

        assertTrue(Mp4MetadataTagger.tag(inputFile, outputFile, metadata, jpegCover))

        val parsed = Mp4MetadataTagger.readTags(outputFile)
        assertEquals("Darsa 3: Tawheed", parsed.title)
        assertEquals("Sheikh Ahmad", parsed.artist)
        assertEquals("Kitabu cha Tawheed", parsed.album)
        assertEquals(3, parsed.trackNumber)
        assertEquals(12, parsed.totalTracks)
        assertEquals(Mp4MetadataTagger.DEFAULT_ENCODER, parsed.encoder)
        assertEquals(jpegCover, parsed.coverArt)

        // Verify both stco and co64 chunk offsets point to the exact unchanged mdat payload
        val stcoOffset = findFirstStcoOffset(outputFile)
        val co64Offset = findFirstCo64Offset(outputFile)
        assertEquals(stcoOffset, co64Offset)
        assertArrayEquals(mediaPayload, readSlice(outputFile, stcoOffset, mediaPayload.size))
    }

    @Test
    fun `tags trailer-moov M4A replacing existing udta with PNG cover art`() {
        val mediaPayload = "TRAILER_MOOV_MEDIA_987654321".toByteArray(StandardCharsets.US_ASCII)
        val inputFile = tempFolder.newFile("trailer_input.m4a")
        writeSyntheticMp4(
            dest = inputFile,
            moovBeforeMdat = false,
            mediaPayload = mediaPayload,
            includeCo64 = false,
            includeDummyUdta = true
        )
        val originalStco = findFirstStcoOffset(inputFile)

        val pngCover = CoverArt(
            bytes = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D
            ),
            isPng = true
        )
        val outputFile = File(tempFolder.root, "trailer_tagged.m4a")
        val metadata = AudioMetadata(
            title = "Audio Clip",
            artist = "Creator",
            album = null,
            trackNumber = 1,
            totalTracks = 1
        )

        assertTrue(Mp4MetadataTagger.tag(inputFile, outputFile, metadata, pngCover))

        val parsed = Mp4MetadataTagger.readTags(outputFile)
        assertEquals("Audio Clip", parsed.title)
        assertEquals("Creator", parsed.artist)
        assertEquals(1, parsed.trackNumber)
        assertEquals(1, parsed.totalTracks)
        assertEquals(pngCover, parsed.coverArt)
        assertNotNull(parsed.encoder)

        // Because mdat precedes moov, its chunk offset must stay identical
        val updatedStco = findFirstStcoOffset(outputFile)
        assertEquals(originalStco, updatedStco)
        assertArrayEquals(mediaPayload, readSlice(outputFile, updatedStco, mediaPayload.size))
    }

    @Test
    fun `rejects non-MP4 files and empty metadata without corrupting input`() {
        val badFile = tempFolder.newFile("bad.m4a").apply { writeText("not an mp4 file") }
        val output = File(tempFolder.root, "bad_out.m4a")
        assertFalse(Mp4MetadataTagger.tag(badFile, output, AudioMetadata(title = "X")))
        assertFalse(output.exists())

        val valid = tempFolder.newFile("valid.m4a")
        writeSyntheticMp4(valid, moovBeforeMdat = true, mediaPayload = "DATA".toByteArray())
        assertFalse(Mp4MetadataTagger.tag(valid, output, AudioMetadata()))
    }

    companion object {
        fun writeSyntheticMp4(
            dest: File,
            moovBeforeMdat: Boolean,
            mediaPayload: ByteArray,
            includeCo64: Boolean = false,
            includeDummyUdta: Boolean = false
        ) {
            val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(StandardCharsets.ISO_8859_1))
            val mdatBox = box("mdat", mediaPayload)

            fun buildMoov(chunkOffset: Long): ByteArray {
                val mvhd = box("mvhd", ByteArray(16))
                val stcoPayload = ByteArrayOutputStream().apply {
                    writeInt32(0) // version+flags
                    writeInt32(1) // entry count
                    writeInt32(chunkOffset.toInt())
                }.toByteArray()
                val stco = box("stco", stcoPayload)

                val stblContent = ByteArrayOutputStream().apply {
                    write(stco)
                    if (includeCo64) {
                        val co64Payload = ByteArrayOutputStream().apply {
                            writeInt32(0)
                            writeInt32(1)
                            writeInt64(chunkOffset)
                        }.toByteArray()
                        write(box("co64", co64Payload))
                    }
                }.toByteArray()

                val stbl = box("stbl", stblContent)
                val minf = box("minf", stbl)
                val mdia = box("mdia", minf)
                val trak = box("trak", mdia)
                val moovContent = ByteArrayOutputStream().apply {
                    write(mvhd)
                    write(trak)
                    if (includeDummyUdta) {
                        write(box("udta", ByteArray(8)))
                    }
                }.toByteArray()
                return box("moov", moovContent)
            }

            if (moovBeforeMdat) {
                val sizeProbe = buildMoov(0L)
                val realMdatPayloadOffset = (ftyp.size + sizeProbe.size + 8).toLong()
                val realMoov = buildMoov(realMdatPayloadOffset)
                dest.writeBytes(ftyp + realMoov + mdatBox)
            } else {
                val realMdatPayloadOffset = (ftyp.size + 8).toLong()
                val realMoov = buildMoov(realMdatPayloadOffset)
                dest.writeBytes(ftyp + mdatBox + realMoov)
            }
        }

        private fun box(type: String, payload: ByteArray): ByteArray {
            val total = 8 + payload.size
            return ByteArrayOutputStream(total).apply {
                writeInt32(total)
                write(type.toByteArray(StandardCharsets.ISO_8859_1))
                write(payload)
            }.toByteArray()
        }

        private fun ByteArrayOutputStream.writeInt32(v: Int) {
            write((v ushr 24) and 0xFF)
            write((v ushr 16) and 0xFF)
            write((v ushr 8) and 0xFF)
            write(v and 0xFF)
        }

        private fun ByteArrayOutputStream.writeInt64(v: Long) {
            writeInt32((v ushr 32).toInt())
            writeInt32((v and 0xFFFFFFFFL).toInt())
        }

        fun findFirstStcoOffset(file: File): Long = findChunkTableOffset(file, "stco", is64Bit = false)

        private fun findFirstCo64Offset(file: File): Long = findChunkTableOffset(file, "co64", is64Bit = true)

        private fun findChunkTableOffset(file: File, targetType: String, is64Bit: Boolean): Long {
            val bytes = file.readBytes()
            val marker = targetType.toByteArray(StandardCharsets.ISO_8859_1)
            for (i in 4..(bytes.size - 16)) {
                if (bytes[i] == marker[0] &&
                    bytes[i + 1] == marker[1] &&
                    bytes[i + 2] == marker[2] &&
                    bytes[i + 3] == marker[3]
                ) {
                    val pos = i + 12
                    return if (!is64Bit) {
                        ((bytes[pos].toLong() and 0xFFL) shl 24) or
                            ((bytes[pos + 1].toLong() and 0xFFL) shl 16) or
                            ((bytes[pos + 2].toLong() and 0xFFL) shl 8) or
                            (bytes[pos + 3].toLong() and 0xFFL)
                    } else {
                        ((bytes[pos].toLong() and 0xFFL) shl 56) or
                            ((bytes[pos + 1].toLong() and 0xFFL) shl 48) or
                            ((bytes[pos + 2].toLong() and 0xFFL) shl 40) or
                            ((bytes[pos + 3].toLong() and 0xFFL) shl 32) or
                            ((bytes[pos + 4].toLong() and 0xFFL) shl 24) or
                            ((bytes[pos + 5].toLong() and 0xFFL) shl 16) or
                            ((bytes[pos + 6].toLong() and 0xFFL) shl 8) or
                            (bytes[pos + 7].toLong() and 0xFFL)
                    }
                }
            }
            error("Could not find $targetType in ${file.name}")
        }

        private fun readSlice(file: File, offset: Long, length: Int): ByteArray {
            val out = ByteArray(length)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                raf.readFully(out)
            }
            return out
        }
    }
}
