package com.mediasaver.core.common

import com.mediasaver.core.model.PlatformId
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Hashing utility for content deduplication and data integrity verification.
 * Implements chunked streaming I/O to avoid buffering large media files into memory.
 */
object HashUtils {

    private const val BUFFER_SIZE = 8192 // 8 KB buffer

    /**
     * Calculates the SHA-256 hexadecimal hash of an input stream.
     * The caller is responsible for opening and closing the stream.
     *
     * @param inputStream The stream to hash
     * @return 64-character lowercase hex SHA-256 checksum
     */
    fun sha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return bytesToHex(digest.digest())
    }

    /**
     * Calculates the SHA-256 hash of a local file.
     *
     * @param file The file to hash
     * @return 64-character lowercase hex SHA-256 checksum
     */
    fun sha256(file: File): String {
        return file.inputStream().use { sha256(it) }
    }

    /**
     * Calculates the SHA-256 hash of a raw byte array.
     */
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return bytesToHex(digest.digest(bytes))
    }

    /**
     * Calculates the SHA-256 hash of a string.
     */
    fun sha256(text: String): String {
        return sha256(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Generates a deterministic duplicate detection key based on platform, media identifier,
     * and optionally canonical URL.
     *
     * @param platform The platform ID
     * @param mediaId The platform-specific media/video ID
     * @param sourceUrl Optional canonical source URL
     * @return A lightweight composite lookup key
     */
    fun generateDuplicateKey(platform: PlatformId, mediaId: String, sourceUrl: String? = null): String {
        val canonicalId = mediaId.trim()
        return if (sourceUrl.isNullOrBlank()) {
            "${platform.name}:$canonicalId"
        } else {
            val hash = sha256("${platform.name}:$canonicalId:${sourceUrl.trim()}")
            "${platform.name}:$canonicalId:$hash"
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        val hexArray = "0123456789abcdef".toCharArray()
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }
}
