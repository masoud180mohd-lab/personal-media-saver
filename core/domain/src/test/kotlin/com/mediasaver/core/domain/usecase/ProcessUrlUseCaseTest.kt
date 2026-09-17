package com.mediasaver.core.domain.usecase

import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.domain.platform.PlatformResolver
import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProcessUrlUseCaseTest {

    private class MockYouTubeAdapter : PlatformAdapter {
        override val platformId = PlatformId.YOUTUBE
        override val platformName = "YouTube"

        var shouldFail = false

        override fun canHandle(url: String): Boolean = url.contains("youtube.com") || url.contains("youtu.be")

        override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
            return if (shouldFail) {
                Result.failure(AppError.ExtractionFailed(PlatformId.YOUTUBE))
            } else {
                Result.success(
                    MediaInfo(
                        id = "vid123",
                        platform = PlatformId.YOUTUBE,
                        title = "Sample Video",
                        sourceUrl = url
                    )
                )
            }
        }

        override suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>> = Result.success(emptyList())
        override suspend fun healthCheck(): AdapterStatus = AdapterStatus.OPERATIONAL
    }

    private lateinit var mockYouTubeAdapter: MockYouTubeAdapter
    private lateinit var resolver: PlatformResolver
    private lateinit var useCase: ProcessUrlUseCase

    @Before
    fun setUp() {
        mockYouTubeAdapter = MockYouTubeAdapter()
        resolver = PlatformResolver(listOf(mockYouTubeAdapter))
        useCase = ProcessUrlUseCase(resolver)
    }

    @Test
    fun `successful URL processing returns MediaInfo`() = runBlocking {
        val result = useCase("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        assertTrue(result.isSuccess)
        val mediaInfo = result.getOrThrow()
        assertEquals("vid123", mediaInfo.id)
        assertEquals(PlatformId.YOUTUBE, mediaInfo.platform)
        assertEquals("Sample Video", mediaInfo.title)
    }

    @Test
    fun `invalid URL fails with InvalidUrl error`() = runBlocking {
        val result = useCase("not_a_valid_url")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is AppError.InvalidUrl)
    }

    @Test
    fun `unsupported platform fails with UnsupportedPlatform error`() = runBlocking {
        val result = useCase("https://vimeo.com/987654321")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is AppError.UnsupportedPlatform)
    }

    @Test
    fun `adapter extraction failure propagates AppError`() = runBlocking {
        mockYouTubeAdapter.shouldFail = true

        val result = useCase("https://www.youtube.com/watch?v=fails")
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is AppError.ExtractionFailed)
    }
}
