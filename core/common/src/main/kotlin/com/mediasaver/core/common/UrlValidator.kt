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

        // Check for CRLF injection, null bytes, or dangerous control characters
        if (trimmed.contains('\r') || trimmed.contains('\n') || trimmed.contains('\u0000')) {
            return Result.failure(AppError.InvalidUrl(trimmed.take(64), "URL contains prohibited control characters."))
        }

        // Immediate check for dangerous schemes (e.g. javascript:, file:, content:, intent:)
        val schemeSeparatorIndex = trimmed.indexOf(':')
        if (schemeSeparatorIndex > 0) {
            val potentialScheme = trimmed.substring(0, schemeSeparatorIndex).trim().lowercase()
            if (SecurityUtils.isDangerousScheme(potentialScheme)) {
                return Result.failure(
                    AppError.InvalidUrl(trimmed.take(64), "Scheme '$potentialScheme:' is strictly prohibited.")
                )
            }
        }

        // Check for obvious path traversal injection sequences in URL
        if (trimmed.contains("/../") || trimmed.contains("\\..\\") || trimmed.contains("/..\\") || trimmed.contains("\\../")) {
            return Result.failure(AppError.InvalidUrl(trimmed.take(64), "URL contains invalid directory traversal patterns."))
        }

        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return Result.failure(AppError.InvalidUrl(trimmed.take(64), "Malformed URL format."))

        val scheme = uri.scheme?.lowercase()
        if (scheme == null || (scheme != "http" && scheme != "https")) {
            return Result.failure(
                AppError.InvalidUrl(trimmed.take(64), "Only HTTP and HTTPS schemes are supported.")
            )
        }

        if (enforceHttps && scheme != "https") {
            return Result.failure(
                AppError.InvalidUrl(trimmed.take(64), "Insecure HTTP connections are disallowed under current security policy.")
            )
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return Result.failure(AppError.InvalidUrl(trimmed.take(64), "URL must contain a valid domain host."))
        }

        // Prevent embedded credentials (userinfo injection)
        if (!uri.rawUserInfo.isNullOrBlank()) {
            return Result.failure(AppError.InvalidUrl(trimmed.take(64), "Credentials in URL are strictly prohibited."))
        }

        // SSRF protection: block local/private IP addresses and localhost
        if (SecurityUtils.isLocalOrPrivateIp(host)) {
            return Result.failure(
                AppError.InvalidUrl(trimmed.take(64), "Access to private or local network hosts is blocked for security.")
            )
        }

        // Upgrade http to https recommendation: if http, upgrade to https
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
