package com.mediasaver.core.common

import com.mediasaver.core.model.PlatformId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNameSanitizerTest {

    @Test
    fun `sanitizes illegal filesystem characters`() {
        val rawTitle = "My Video: Episode 1 / Special Edition *NEW* ? \"Awesome\" <Watch> | OK"
        val sanitized = FileNameSanitizer.sanitize(rawTitle)

        assertFalse(sanitized.contains(':'))
        assertFalse(sanitized.contains('/'))
        assertFalse(sanitized.contains('\\'))
        assertFalse(sanitized.contains('*'))
        assertFalse(sanitized.contains('?'))
        assertFalse(sanitized.contains('"'))
        assertFalse(sanitized.contains('<'))
        assertFalse(sanitized.contains('>'))
        assertFalse(sanitized.contains('|'))
    }

    @Test
    fun `handles blank or null titles with fallback`() {
        assertEquals("media", FileNameSanitizer.sanitize(null))
        assertEquals("media", FileNameSanitizer.sanitize(""))
        assertEquals("media", FileNameSanitizer.sanitize("    "))
        assertEquals("custom_fallback", FileNameSanitizer.sanitize("   ", fallback = "custom_fallback"))
    }

    @Test
    fun `truncates excessively long titles`() {
        val longTitle = "A".repeat(300)
        val sanitized = FileNameSanitizer.sanitize(longTitle)

        assertTrue(sanitized.length <= FileNameSanitizer.MAX_BASE_NAME_LENGTH)
        assertEquals(FileNameSanitizer.MAX_BASE_NAME_LENGTH, sanitized.length)
    }

    @Test
    fun `buildFileName constructs proper platform prefixed name`() {
        val fileName = FileNameSanitizer.buildFileName(
            title = "My Cool Video",
            platform = PlatformId.YOUTUBE,
            extension = "mp4",
            id = "video123"
        )

        assertEquals("YouTube_My Cool Video_video123.mp4", fileName)
    }

    @Test
    fun `buildFileName normalizes extensions properly`() {
        val fileNameWithDot = FileNameSanitizer.buildFileName(
            title = "Sample",
            platform = PlatformId.TIKTOK,
            extension = ".MP4"
        )
        assertEquals("TikTok_Sample.mp4", fileNameWithDot)

        val fileNameBlankExt = FileNameSanitizer.buildFileName(
            title = "Sample",
            platform = PlatformId.WHATSAPP,
            extension = ""
        )
        assertEquals("WhatsApp_Sample.mp4", fileNameBlankExt)
    }

    @Test
    fun `neutralizes path traversal attempts in filename`() {
        val traversalTitle = "../../../../../etc/passwd"
        val sanitized = FileNameSanitizer.sanitize(traversalTitle)

        assertFalse(sanitized.contains(".."))
        assertFalse(sanitized.contains("/"))
        assertFalse(sanitized.contains("\\"))
    }

    @Test
    fun `replaces Windows reserved names with fallback`() {
        assertEquals("media", FileNameSanitizer.sanitize("CON"))
        assertEquals("media", FileNameSanitizer.sanitize("prn"))
        assertEquals("media", FileNameSanitizer.sanitize("aux"))
        assertEquals("media", FileNameSanitizer.sanitize("NUL"))
        assertEquals("media", FileNameSanitizer.sanitize("com1"))
        assertEquals("media", FileNameSanitizer.sanitize("LPT1"))
    }

    @Test
    fun `rejects dangerous extensions and defaults to mp4`() {
        val maliciousExe = FileNameSanitizer.buildFileName(
            title = "Innocent",
            platform = PlatformId.YOUTUBE,
            extension = "exe"
        )
        assertEquals("YouTube_Innocent.mp4", maliciousExe)

        val maliciousSh = FileNameSanitizer.buildFileName(
            title = "Innocent",
            platform = PlatformId.YOUTUBE,
            extension = ".sh"
        )
        assertEquals("YouTube_Innocent.mp4", maliciousSh)

        val maliciousApk = FileNameSanitizer.buildFileName(
            title = "Innocent",
            platform = PlatformId.YOUTUBE,
            extension = "apk"
        )
        assertEquals("YouTube_Innocent.mp4", maliciousApk)
    }
}
