package com.mediasaver.core.common

import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test
    fun `validates and normalizes valid https URL`() {
        val input = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val result = UrlValidator.validate(input)

        assertTrue(result.isSuccess)
        val validated = result.getOrThrow()
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", validated.normalizedUrl)
        assertEquals(PlatformId.YOUTUBE, validated.platform)
        assertTrue(validated.isHttps)
    }

    @Test
    fun `upgrades valid http URL to https`() {
        val input = "http://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val result = UrlValidator.validate(input)

        assertTrue(result.isSuccess)
        val validated = result.getOrThrow()
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", validated.normalizedUrl)
        assertTrue(validated.isHttps)
    }

    @Test
    fun `rejects blank and whitespace-only URLs`() {
        val emptyResult = UrlValidator.validate("")
        assertTrue(emptyResult.isFailure)
        assertTrue(emptyResult.exceptionOrNull() is AppError.InvalidUrl)

        val blankResult = UrlValidator.validate("   \n\t  ")
        assertTrue(blankResult.isFailure)
        assertTrue(blankResult.exceptionOrNull() is AppError.InvalidUrl)

        val nullResult = UrlValidator.validate(null)
        assertTrue(nullResult.isFailure)
    }

    @Test
    fun `rejects URLs exceeding 2048 characters`() {
        val longUrl = "https://www.youtube.com/watch?v=" + "a".repeat(2050)
        val result = UrlValidator.validate(longUrl)

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is AppError.InvalidUrl)
        assertTrue(error?.message?.contains("maximum allowed length") == true)
    }

    @Test
    fun `rejects non-http non-https schemes`() {
        val ftpResult = UrlValidator.validate("ftp://example.com/video.mp4")
        assertTrue(ftpResult.isFailure)
        assertTrue(ftpResult.exceptionOrNull() is AppError.InvalidUrl)

        val javascriptResult = UrlValidator.validate("javascript:alert(1)")
        assertTrue(javascriptResult.isFailure)

        val fileResult = UrlValidator.validate("file:///sdcard/video.mp4")
        assertTrue(fileResult.isFailure)
    }

    @Test
    fun `enforceHttps rejects raw http when flag is enabled`() {
        val input = "http://example.com/media"
        val result = UrlValidator.validate(input, enforceHttps = true)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AppError.InvalidUrl)
    }

    @Test
    fun `isValid helper returns correct boolean`() {
        assertTrue(UrlValidator.isValid("https://tiktok.com/@user/video/123"))
        assertFalse(UrlValidator.isValid(""))
        assertFalse(UrlValidator.isValid("not a url"))
    }
}
