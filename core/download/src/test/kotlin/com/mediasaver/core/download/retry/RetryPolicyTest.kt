package com.mediasaver.core.download.retry

import com.mediasaver.core.model.error.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RetryPolicyTest {

    private lateinit var retryPolicy: RetryPolicy

    @Before
    fun setUp() {
        retryPolicy = RetryPolicy()
    }

    @Test
    fun `shouldRetry returns true for transient network errors under limit`() {
        assertTrue(retryPolicy.shouldRetry(0, AppError.NetworkError()))
        assertTrue(retryPolicy.shouldRetry(1, SocketTimeoutException("timeout")))
        assertTrue(retryPolicy.shouldRetry(2, IOException("connection reset")))
    }

    @Test
    fun `shouldRetry returns false when maximum retries reached`() {
        assertFalse(retryPolicy.shouldRetry(3, AppError.NetworkError()))
        assertFalse(retryPolicy.shouldRetry(4, IOException()))
    }

    @Test
    fun `shouldRetry returns false for non-retryable 404 and 403 HTTP errors`() {
        assertFalse(retryPolicy.shouldRetry(0, AppError.DownloadFailed(404)))
        assertFalse(retryPolicy.shouldRetry(0, AppError.DownloadFailed(403)))
        assertFalse(retryPolicy.shouldRetry(0, AppError.DownloadFailed(401)))
    }

    @Test
    fun `shouldRetry returns true for 5xx server errors and 429 rate limits`() {
        assertTrue(retryPolicy.shouldRetry(0, AppError.DownloadFailed(500)))
        assertTrue(retryPolicy.shouldRetry(1, AppError.DownloadFailed(503)))
        assertTrue(retryPolicy.shouldRetry(0, AppError.DownloadFailed(429)))
    }

    @Test
    fun `shouldRetry returns false for permanent client domain errors`() {
        assertFalse(retryPolicy.shouldRetry(0, AppError.InvalidUrl("invalid://url")))
        assertFalse(retryPolicy.shouldRetry(0, AppError.UnsupportedPlatform("unknown")))
        assertFalse(retryPolicy.shouldRetry(0, AppError.StorageFull(1000L)))
        assertFalse(retryPolicy.shouldRetry(0, AppError.PermissionDenied()))
        assertFalse(retryPolicy.shouldRetry(0, AppError.MediaNotFound()))
        assertFalse(retryPolicy.shouldRetry(0, AppError.ContentRestricted()))
    }

    @Test
    fun `getBackoffDelayMillis follows exponential backoff sequence`() {
        val error = AppError.NetworkError()
        assertEquals(1000L, retryPolicy.getBackoffDelayMillis(0, error))
        assertEquals(2000L, retryPolicy.getBackoffDelayMillis(1, error))
        assertEquals(4000L, retryPolicy.getBackoffDelayMillis(2, error))
        assertEquals(8000L, retryPolicy.getBackoffDelayMillis(3, error))
        assertEquals(16000L, retryPolicy.getBackoffDelayMillis(4, error))
        assertEquals(32000L, retryPolicy.getBackoffDelayMillis(5, error))
        // Beyond bounds should clamp to the last delay
        assertEquals(32000L, retryPolicy.getBackoffDelayMillis(10, error))
    }

    @Test
    fun `getBackoffDelayMillis returns extended 30s delay for 429 rate limit`() {
        val rateLimitError = AppError.DownloadFailed(429)
        assertEquals(30000L, retryPolicy.getBackoffDelayMillis(0, rateLimitError))
        assertEquals(30000L, retryPolicy.getBackoffDelayMillis(1, rateLimitError))
    }
}
