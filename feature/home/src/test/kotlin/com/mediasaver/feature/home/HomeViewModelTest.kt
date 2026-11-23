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
    private val settings = object : SettingsRepository {
        override val settings = flowOf(AppSettings())
        override suspend fun getSettings() = AppSettings()
        override suspend fun updatePreferredQuality(quality: String) {}
        override suspend fun updateDownloadDirectoryUri(uri: String?) {}
        override suspend fun updateMaxConcurrentDownloads(limit: Int) {}
        override suspend fun updateWifiOnlyDownload(wifiOnly: Boolean) {}
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
}
