package com.mediasaver.core.common

import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import java.net.URI

/**
 * Validated result containing the normalized URL and its recognized platform.
 */
data class ValidatedUrl(
    val normalizedUrl: String,
    val platform: PlatformId,
    val isHttps: Boolean
)

/**
 * Strict URL sanitization and validation utility conforming to the Zero-Trust security rules.
 *
 * Enforces:
 * - Rejecting blank or whitespace-only inputs
 * - Maximum length cap of 2048 characters to prevent buffer and regex exhaustion
 * - Scheme restrictions (http/https only, with HTTPS recommended and upgraded where possible)
 * - Basic URI syntax validation
 * - Platform resolution check
 */
object UrlValidator {

    const val MAX_URL_LENGTH = 2048

    /**
     * Validates an input string against URL safety rules.
     *
     * @param rawUrl The unvalidated input string from user paste or Share Sheet
     * @param enforceHttps If true, http schemes will be rejected unless upgraded to https (default: false)
     * @return [Result] containing [ValidatedUrl] on success, or [AppError] on failure
     */
    fun validate(rawUrl: String?, enforceHttps: Boolean = false): Result<ValidatedUrl> {
        if (rawUrl.isNullOrBlank()) {
            return Result.failure(AppError.InvalidUrl("", "URL cannot be blank or empty."))
        }

        val trimmed = rawUrl.trim()

        if (trimmed.length > MAX_URL_LENGTH) {
            return Result.failure(
                AppError.InvalidUrl(
                    trimmed.take(64) + "...",
                    "URL exceeds maximum allowed length of $MAX_URL_LENGTH characters."
                )
            )
        }

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return Result.failure(AppError.InvalidUrl(trimmed, "Malformed URL format."))

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || (scheme != "http" && scheme != "https")) {
            return Result.failure(
                AppError.InvalidUrl(trimmed, "Only HTTP and HTTPS schemes are supported.")
            )
        }

        if (enforceHttps && scheme != "https") {
            return Result.failure(
                AppError.InvalidUrl(trimmed, "Insecure HTTP connections are disallowed under current security policy.")
            )
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return Result.failure(AppError.InvalidUrl(trimmed, "URL must contain a valid domain host."))
        }

        // Upgrade http to https recommendation: if http, upgrade to https for known media platforms
        val normalizedUrl = if (scheme == "http") {
            "https://" + trimmed.removePrefix("http://")
        } else {
            trimmed
        }

        val platform = PlatformDetector.detect(normalizedUrl)

        return Result.success(
            ValidatedUrl(
                normalizedUrl = normalizedUrl,
                platform = platform,
                isHttps = true
            )
        )
    }

    /**
     * Quick check if the URL is non-empty, within size bounds, and syntactically valid.
     */
    fun isValid(url: String?): Boolean = validate(url).isSuccess
}
