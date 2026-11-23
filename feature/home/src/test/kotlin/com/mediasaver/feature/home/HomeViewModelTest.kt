package com.mediasaver.feature.home

import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.domain.platform.PlatformResolver
import com.mediasaver.core.domain.repository.DownloadRepository
import com.mediasaver.core.domain.repository.SettingsRepository
import com.mediasaver.core.domain.usecase.*
import com.mediasaver.core.model.*
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import android.os.Looper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HomeViewModelTest {
    private val url = "https://youtube.com/watch?v=sample"
    private var queued: Pair<StreamInfo, StreamInfo?>? = null
    private val queuedTasks = mutableListOf<DownloadTask>()
    private val duplicateQualities = mutableSetOf<String>()
    private val downloads = object : DownloadRepository {
        override fun getActiveDownloads() = emptyFlow<List<DownloadTask>>()
        override fun getAllDownloads() = getActiveDownloads()
        override fun getCompletedDownloads() = getActiveDownloads()
        override fun observeProgress() = emptyFlow<DownloadProgressInfo>()
        override suspend fun enqueue(task: DownloadTask, videoStream: StreamInfo, audioStream: StreamInfo?) {
            if (videoStream.quality in duplicateQualities) throw AppError.DuplicateDownload()
            queued = videoStream to audioStream
            queuedTasks.add(task)
        }
        override suspend fun cancel(downloadId: String) {}
        override suspend fun retry(downloadId: String) {}
        override suspend fun delete(downloadId: String) {}
        override suspend fun clearHistory() {}
    }
    private var currentSettings = AppSettings()
    private val settingsFlow = kotlinx.coroutines.flow.MutableStateFlow(currentSettings)
    private val settings = object : SettingsRepository {
        override val settings = settingsFlow
        override suspend fun getSettings() = currentSettings
        override suspend fun updatePreferredQuality(quality: String) {
            currentSettings = currentSettings.copy(preferredQuality = quality)
            settingsFlow.value = currentSettings
        }
        override suspend fun updateDownloadDirectoryUri(uri: String?) {}
        override suspend fun updateMaxConcurrentDownloads(limit: Int) {}
        override suspend fun updateWifiOnlyDownload(wifiOnly: Boolean) {
            currentSettings = currentSettings.copy(wifiOnlyDownload = wifiOnly)
            settingsFlow.value = currentSettings
        }
        override suspend fun updateMaxDownloadSizeMb(maxSizeMb: Int) {}
        override suspend fun updateWhatsAppStatusUri(uri: String?) {}
        override suspend fun updateWhatsAppBusinessStatusUri(uri: String?) {}
        override suspend fun updateSaveDownloadHistory(enabled: Boolean) {}
        override suspend fun updateAutoDeleteTempFiles(enabled: Boolean) {}
        override suspend fun updateThemeMode(themeMode: ThemeMode) {}
        override suspend fun resetToDefaults() {}
    }

    private fun load(result: Result<List<StreamInfo>>): HomeViewModel {
        val adapter = object : PlatformAdapter {
            override val platformId = PlatformId.YOUTUBE
            override val platformName = "YouTube"
            override fun canHandle(url: String) = true
            override suspend fun extractMediaInfo(url: String) = Result.success(MediaInfo("sample", platformId, "title", sourceUrl = url))
            override suspend fun resolveStreams(mediaId: String) = result
            override suspend fun healthCheck() = AdapterStatus.OPERATIONAL
        }
        val resolver = PlatformResolver(listOf(adapter))
        val vm = HomeViewModel(ProcessUrlUseCase(resolver), ResolveStreamsUseCase(resolver),
            EnqueueDownloadUseCase(downloads), GetSettingsUseCase(settings))
        vm.onUrlChange(url)
        vm.processUrl()
        shadowOf(Looper.getMainLooper()).idle()
        return vm
    }

    @Test fun `failed and empty resolution preserve metadata and cannot enqueue`() {
        for (result in listOf(Result.failure(IllegalStateException("Unavailable")), Result.success(emptyList<StreamInfo>()))) {
            val vm = load(result)
            val state = vm.uiState.value
            assertNotNull(state.mediaInfo)
            assertNull(state.selectedStream)
            assertTrue(state.availableStreams.isEmpty())
            assertFalse(state.errorMessage.isNullOrBlank())
            vm.startDownload()
            assertNull(queued)
        }
    }

    @Test fun `adaptive downloads choose the highest compatible audio track`() {
        val aac = StreamInfo("aac", format = "m4a", codec = "mp4a.40.2", isAudioOnly = true, bitrate = 128)
        val opus = StreamInfo("opus", format = "webm", codec = "opus", isAudioOnly = true, bitrate = 256)
        for ((format, expected) in listOf("mp4" to aac, "webm" to opus)) {
            val video = StreamInfo("video", format = format, isVideoOnly = true)
            val vm = load(Result.success(listOf(video, opus, aac.copy(bitrate = 64), aac)))
            vm.startDownload(video)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(video to expected, queued)
            assertNotNull(vm.uiState.value.downloadEnqueuedMessage)
        }
    }

    @Test fun `startBatchDownload skips already saved slides and warns when all are duplicates`() {
        val slides = listOf(
            StreamInfo("https://cdn.example/s1.jpg", quality = "Slide 1", format = "jpg", mimeType = "image/jpeg", mediaIndex = 0),
            StreamInfo("https://cdn.example/s2.jpg", quality = "Slide 2", format = "jpg", mimeType = "image/jpeg", mediaIndex = 1),
            StreamInfo("https://cdn.example/s3.mp4", quality = "Slide 3", format = "mp4", mimeType = "video/mp4", mediaIndex = 2)
        )
        queuedTasks.clear()
        duplicateQualities.clear()
        duplicateQualities.add("Slide 1")

        val vm = load(Result.success(slides))
        vm.startBatchDownload(slides)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf("Slide 2", "Slide 3"), queuedTasks.map { it.selectedQuality })
        assertEquals(listOf("image/jpeg", "video/mp4"), queuedTasks.map { it.mimeType })
        assertEquals("Queued 2 items (1 already saved)", vm.uiState.value.downloadEnqueuedMessage)
        assertNull(vm.uiState.value.errorMessage)

        duplicateQualities.addAll(listOf("Slide 2", "Slide 3"))
        vm.clearEnqueuedMessage()
        vm.startBatchDownload(slides)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(vm.uiState.value.downloadEnqueuedMessage)
        assertEquals("All slides are already queued or saved.", vm.uiState.value.errorMessage)
    }

    @Test fun `selectPreferredStream matches prefix 1080p60 MP4 prefers progressive 720p and falls back within limit`() = kotlinx.coroutines.runBlocking {
        val updateUseCase = UpdateSettingsUseCase(settings)
        updateUseCase.updateWifiOnlyDownload(true)
        assertTrue(currentSettings.wifiOnlyDownload)

        val progressive360 = StreamInfo("https://example/360.mp4", quality = "360p", format = "mp4", isVideoOnly = false)
        val progressive720 = StreamInfo("https://example/720.mp4", quality = "720p", format = "mp4", isVideoOnly = false)
        val adaptive720 = StreamInfo("https://example/720v.mp4", quality = "720p", format = "mp4", isVideoOnly = true)
        val adaptive1080Webm = StreamInfo("https://example/1080.webm", quality = "1080p60", format = "webm", isVideoOnly = true)
        val adaptive1080Mp4 = StreamInfo("https://example/1080.mp4", quality = "1080p60", format = "mp4", isVideoOnly = true)
        val audio = StreamInfo("https://example/audio.m4a", quality = "128 kbps", format = "m4a", isAudioOnly = true)

        updateUseCase.updatePreferredQuality("1080p")
        val vm1080 = load(Result.success(listOf(audio, progressive360, adaptive1080Webm, adaptive1080Mp4, progressive720)))
        assertEquals(adaptive1080Mp4, vm1080.uiState.value.selectedStream)

        updateUseCase.updatePreferredQuality("720p")
        val vm720 = load(Result.success(listOf(audio, progressive360, adaptive720, progressive720, adaptive1080Mp4)))
        assertEquals(progressive720, vm720.uiState.value.selectedStream)

        // When exact/prefix match for 480p is absent, pick highest resolution <= 480p (360p)
        val fallback480 = vm720.selectPreferredStream(listOf(audio, adaptive1080Mp4, progressive360, progressive720), "480p")
        assertEquals(progressive360, fallback480)
    }
}
