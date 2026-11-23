package com.mediasaver.core.download.muxing

import com.mediasaver.core.model.error.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface defining the audio/video multiplexing engine.
 */
interface MuxingEngine {
    /**
     * Combines separate audio and video streams into a unified MP4 file without re-encoding.
     *
     * @param videoFile Temporary file containing video stream
     * @param audioFile Temporary file containing audio stream
     * @param outputFile Destination MP4 file
     * @param onProgress Callback receiving progress percentage (0..100)
     */
    suspend fun muxAudioVideo(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: ((progressPercent: Int) -> Unit)? = null
    ): Result<File>
}

@Singleton
class DefaultMuxingEngine @Inject constructor() : MuxingEngine {
    override suspend fun muxAudioVideo(videoFile: File, audioFile: File, outputFile: File,
        onProgress: ((Int) -> Unit)?): Result<File> = withContext(Dispatchers.IO) {
        var complete = false
        try {
            withExtractor(videoFile) { video ->
                withExtractor(audioFile) { audio ->
                    val vf = select(video, "video/")
                    val af = select(audio, "audio/")
                    val container = containerFor(vf.getString(MediaFormat.KEY_MIME), af.getString(MediaFormat.KEY_MIME))
                    val muxer = MediaMuxer(outputFile.path, container)
                    try {
                        val vt = muxer.addTrack(vf)
                        val at = muxer.addTrack(af)
                        if (container == MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 && vf.containsKey(MediaFormat.KEY_ROTATION)) {
                            muxer.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION))
                        }
                        val duration = listOf(vf, af).filter { it.containsKey(MediaFormat.KEY_DURATION) }
                            .maxOfOrNull { it.getLong(MediaFormat.KEY_DURATION) } ?: 0L
                        var buffer = ByteBuffer.allocateDirect(listOf(vf, af).filter { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }
                            .maxOfOrNull { it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }?.coerceIn(65536, MAX_SAMPLE) ?: 2 * 1024 * 1024)
                        val info = MediaCodec.BufferInfo()
                        check(video.sampleTime >= 0 && audio.sampleTime >= 0) { "Empty input track" }
                        muxer.start()
                        var progress = -1
                        while (video.sampleTime >= 0 || audio.sampleTime >= 0) {
                            ensureActive()
                            val fromVideo = audio.sampleTime < 0 || (video.sampleTime >= 0 && video.sampleTime <= audio.sampleTime)
                            val input = if (fromVideo) video else audio
                            if (Build.VERSION.SDK_INT >= 28 && input.sampleSize > buffer.capacity()) {
                                require(input.sampleSize <= MAX_SAMPLE) { "Oversized media sample" }
                                buffer = ByteBuffer.allocateDirect(input.sampleSize.toInt())
                            }
                            buffer.clear()
                            val bytes = input.readSampleData(buffer, 0)
                            check(bytes in 1..buffer.capacity()) { "Unreadable media sample" }
                            check(input.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "Encrypted track" }
                            val flags = if (input.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            info.set(0, bytes, input.sampleTime, flags)
                            muxer.writeSampleData(if (fromVideo) vt else at, buffer, info)
                            if (duration > 0) {
                                val percent = (input.sampleTime * 99 / duration).coerceIn(0, 99).toInt()
                                if (percent > progress) { progress = percent; onProgress?.invoke(percent) }
                            }
                            input.advance()
                        }
                        muxer.stop()
                    } finally {
                        muxer.release()
                    }
                }
            }
            ensureActive()
            verifyOutput(outputFile)
            ensureActive()
            onProgress?.invoke(100)
            complete = true
            videoFile.delete()
            audioFile.delete()
            Result.success(outputFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(AppError.MuxingFailed(e))
        } finally {
            if (!complete) outputFile.delete()
        }
    }

    internal fun verifyOutput(file: File) {
        withExtractor(file) { output ->
            val mimes = (0 until output.trackCount).mapNotNull {
                output.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
            }
            check(mimes.any { it.startsWith("video/") } && mimes.any { it.startsWith("audio/") }) {
                "Muxed output is missing a media track"
            }
        }
    }

    private inline fun <T> withExtractor(file: File, block: (MediaExtractor) -> T): T {
        require(file.isFile && file.length() > 0)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return block(extractor)
        } finally {
            extractor.release()
        }
    }

    private fun select(extractor: MediaExtractor, prefix: String): MediaFormat {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) {
                extractor.selectTrack(i)
                return format
            }
        }
        error("Missing $prefix track")
    }

    internal fun containerFor(video: String?, audio: String?) = when {
        video in setOf("video/avc", "video/hevc", "video/mp4v-es") && audio == "audio/mp4a-latm" ->
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        video in setOf("video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9") && audio in setOf("audio/opus", "audio/vorbis") ->
            MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
        else -> error("Incompatible audio and video codecs")
    }

    private companion object { const val MAX_SAMPLE = 32 * 1024 * 1024 }
}
