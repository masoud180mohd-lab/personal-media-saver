package com.mediasaver.core.download.muxing

import android.media.MediaMuxer
import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MuxingEngineTest {
    @get:Rule val folder = TemporaryFolder()
    private val engine = DefaultMuxingEngine()

    @Test fun `container selection requires a supported pair`() {
        assertEquals(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, engine.containerFor("video/avc", "audio/mp4a-latm"))
        assertEquals(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, engine.containerFor("video/x-vnd.on2.vp9", "audio/opus"))
        assertThrows(IllegalStateException::class.java) { engine.containerFor("video/avc", "audio/opus") }
        assertThrows(IllegalStateException::class.java) { engine.containerFor("video/avc", null) }
    }

    @Test fun `output verification rejects a video-only container and accepts two tracks`() {
        val file = folder.newFile().apply { writeBytes(byteArrayOf(1)) }
        val source = org.robolectric.shadows.util.DataSource.toDataSource(file.path)
        org.robolectric.shadows.ShadowMediaExtractor.addTrack(source,
            android.media.MediaFormat.createVideoFormat("video/avc", 16, 16), byteArrayOf(1))
        assertThrows(IllegalStateException::class.java) { engine.verifyOutput(file) }
        org.robolectric.shadows.ShadowMediaExtractor.addTrack(source,
            android.media.MediaFormat.createAudioFormat("audio/mp4a-latm", 44100, 2), byteArrayOf(2))
        engine.verifyOutput(file)
    }

    @Test fun `missing and invalid tracks never produce a silent copy`() = runBlocking {
        val video = folder.newFile().apply { writeText("invalid media") }
        val audio = File(folder.root, "audio")
        val out = File(folder.root, "out")
        repeat(2) {
            val error = engine.muxAudioVideo(video, audio, out).exceptionOrNull()
            assertTrue(error is AppError.MuxingFailed)
            assertFalse(out.exists())
            assertTrue(video.exists())
            audio.writeText("invalid audio")
        }
    }
}
