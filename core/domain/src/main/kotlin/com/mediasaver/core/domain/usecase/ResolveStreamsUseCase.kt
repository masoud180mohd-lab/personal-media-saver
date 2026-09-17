package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.platform.PlatformResolver
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import javax.inject.Inject

/**
 * Use case to resolve stream options (qualities, formats, audio/video) for a given media content.
 */
class ResolveStreamsUseCase @Inject constructor(
    private val platformResolver: PlatformResolver
) {
    suspend operator fun invoke(mediaId: String, url: String): Result<List<StreamInfo>> {
        val adapter = platformResolver.resolve(url)
            ?: return Result.failure(AppError.UnsupportedPlatform(url))
        return adapter.resolveStreams(mediaId)
    }
}
