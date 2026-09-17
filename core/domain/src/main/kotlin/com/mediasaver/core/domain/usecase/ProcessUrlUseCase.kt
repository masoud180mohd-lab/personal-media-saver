package com.mediasaver.core.domain.usecase

import com.mediasaver.core.common.UrlValidator
import com.mediasaver.core.domain.platform.PlatformResolver
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import javax.inject.Inject

/**
 * Use case orchestrating URL validation, platform detection, adapter resolution,
 * and media metadata extraction.
 */
class ProcessUrlUseCase @Inject constructor(
    private val platformResolver: PlatformResolver
) {

    /**
     * Executes the URL processing pipeline.
     *
     * @param rawUrl The raw URL provided via user input or Share Sheet
     * @return [Result] containing [MediaInfo] on success, or [AppError] on failure
     */
    suspend operator fun invoke(rawUrl: String?): Result<MediaInfo> {
        // Step 1: Validate URL syntax, bounds, and protocol
        val validationResult = UrlValidator.validate(rawUrl)
        if (validationResult.isFailure) {
            val error = validationResult.exceptionOrNull() as? AppError
                ?: AppError.InvalidUrl(rawUrl.orEmpty())
            return Result.failure(error)
        }
        val validated = validationResult.getOrThrow()

        // Step 2: Detect platform
        val platform = validated.platform
        if (platform == PlatformId.UNKNOWN) {
            return Result.failure(AppError.UnsupportedPlatform(validated.normalizedUrl))
        }

        // Step 3: Resolve adapter
        val adapter = platformResolver.resolve(validated.normalizedUrl)
            ?: return Result.failure(AppError.UnsupportedPlatform(validated.normalizedUrl))

        // Step 4: Call extractMediaInfo
        return adapter.extractMediaInfo(validated.normalizedUrl)
    }
}
