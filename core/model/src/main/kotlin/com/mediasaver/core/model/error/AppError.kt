package com.mediasaver.core.model.error

import com.mediasaver.core.model.PlatformId

/**
 * Sealed hierarchy of domain-level errors.
 * Guarantees that errors can be cleanly mapped to user-facing UI messages without leaking raw stack traces.
 */
sealed class AppError(
    open val userMessage: String,
    override val cause: Throwable? = null
) : Exception(userMessage, cause) {

    data class InvalidUrl(
        val url: String,
        override val userMessage: String = "The provided URL is invalid or malformed: $url"
    ) : AppError(userMessage)

    data class UnsupportedPlatform(
        val domain: String,
        override val userMessage: String = "This platform is not currently supported: $domain"
    ) : AppError(userMessage)

    data class MediaNotFound(
        val url: String,
        override val userMessage: String = "Could not locate media at this address. The content may have been deleted."
    ) : AppError(userMessage)

    data class ExtractionFailed(
        val platform: PlatformId,
        override val cause: Throwable? = null,
        override val userMessage: String = "Unable to process content from ${platform.name}. The platform may have updated its layout."
    ) : AppError(userMessage, cause)

    data class ContentRestricted(
        val reason: String,
        override val userMessage: String = "Content cannot be accessed: $reason"
    ) : AppError(userMessage)

    data class NetworkError(
        override val cause: Throwable? = null,
        override val userMessage: String = "Network connection failed. Please check your internet connectivity."
    ) : AppError(userMessage, cause)

    data class DownloadFailed(
        val httpCode: Int? = null,
        override val cause: Throwable? = null,
        override val userMessage: String = if (httpCode != null) {
            "Download failed with HTTP error code $httpCode."
        } else {
            "Download failed due to an unexpected transfer error."
        }
    ) : AppError(userMessage, cause)

    data class DownloadTimeout(
        val url: String,
        override val userMessage: String = "The download timed out while waiting for data transfer."
    ) : AppError(userMessage)

    data class StorageFull(
        val requiredBytes: Long,
        override val userMessage: String = "Insufficient storage space available on the device."
    ) : AppError(userMessage)

    data class PermissionDenied(
        val permission: String,
        override val userMessage: String = "Required storage or folder access was denied ($permission)."
    ) : AppError(userMessage)

    data class FileWriteFailed(
        val path: String? = null,
        override val cause: Throwable? = null,
        override val userMessage: String = "Failed to save file to local media storage."
    ) : AppError(userMessage, cause)

    data class MuxingFailed(
        override val cause: Throwable? = null,
        override val userMessage: String = "Failed to combine audio and video streams into final MP4 container."
    ) : AppError(userMessage, cause)

    data class Unknown(
        override val cause: Throwable? = null,
        override val userMessage: String = "An unexpected error occurred: ${cause?.message ?: "Unknown error"}"
    ) : AppError(userMessage, cause)
}
