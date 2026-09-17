package com.mediasaver.core.common

import com.mediasaver.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class HashUtilsTest {

    @Test
    fun `sha256 produces expected hex string for known input`() {
        // Standard SHA-256 for empty string:
        // e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        val emptyHash = HashUtils.sha256("")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptyHash)

        // SHA-256 for "hello world"
        val helloHash = HashUtils.sha256("hello world")
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", helloHash)
    }

    @Test
    fun `sha256 streaming produces identical hash to byte array`() {
        val testData = "Personal Media Saver Streaming Hash Test Data".toByteArray(Charsets.UTF_8)
        val streamHash = HashUtils.sha256(ByteArrayInputStream(testData))
        val directHash = HashUtils.sha256(testData)

        assertEquals(directHash, streamHash)
    }

    @Test
    fun `generateDuplicateKey returns deterministic output`() {
        val key1 = HashUtils.generateDuplicateKey(PlatformId.YOUTUBE, "video_123")
        val key2 = HashUtils.generateDuplicateKey(PlatformId.YOUTUBE, "video_123")
        assertEquals("YOUTUBE:video_123", key1)
        assertEquals(key1, key2)

        val keyWithUrl1 = HashUtils.generateDuplicateKey(
            PlatformId.TIKTOK,
            "7123456",
            "https://www.tiktok.com/@user/video/7123456"
        )
        val keyWithUrl2 = HashUtils.generateDuplicateKey(
            PlatformId.TIKTOK,
            "7123456",
            "https://www.tiktok.com/@user/video/7123456"
        )
        assertEquals(keyWithUrl1, keyWithUrl2)
        assertTrue(keyWithUrl1.startsWith("TIKTOK:7123456:"))

        val differentMediaKey = HashUtils.generateDuplicateKey(PlatformId.YOUTUBE, "video_456")
        assertNotEquals(key1, differentMediaKey)
    }
}
