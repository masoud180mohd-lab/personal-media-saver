package com.mediasaver.core.download.retry

import com.mediasaver.core.model.error.AppError
import java.io.IOException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Intelligent retry policy managing automatic backoff schedules and failure eligibility.
 */
@Singleton
class RetryPolicy @Inject constructor() {

    companion object {
        const val MAX_AUTOMATIC_RETRIES = 3
        private val BACKOFF_DELAYS = listOf(1000L, 2000L, 4000L, 8000L, 16000L, 32000L)
        const val RATE_LIMIT_BACKOFF = 30000L // 30 seconds for HTTP 429
    }

    /**
     * Determines whether an automatic retry should be attempted.
     *
     * @param attempt Current retry attempt index (0-based)
     * @param error The failure exception
     * @return True if eligible for automatic retry
     */
    fun shouldRetry(attempt: Int, error: Throwable): Boolean {
        if (attempt >= MAX_AUTOMATIC_RETRIES) return false

        return when (error) {
            is AppError.DownloadFailed -> {
                when (error.httpCode) {
                    404 -> false // Content not found
                    403 -> false // Access forbidden (e.g. token expired, blocked)
                    401 -> false // Unauthorized
                    429 -> true  // Rate limited
                    in 500..599 -> true // Server errors
                    else -> true
                }
            }
            is AppError.InvalidUrl,
            is AppError.UnsupportedPlatform,
            is AppError.MediaNotFound,
            is AppError.ContentRestricted,
            is AppError.StorageFull,
            is AppError.PermissionDenied -> false

            is AppError.NetworkError,
            is AppError.DownloadTimeout,
            is SocketTimeoutException,
            is IOException -> true

            else -> false
        }
    }

    /**
     * Computes the backoff delay before the next retry attempt.
     */
    fun getBackoffDelayMillis(attempt: Int, error: Throwable): Long {
        if (error is AppError.DownloadFailed && error.httpCode == 429) {
            return RATE_LIMIT_BACKOFF
        }

        val index = attempt.coerceIn(0, BACKOFF_DELAYS.lastIndex)
        return BACKOFF_DELAYS[index]
    }
}
