---
name: stream-extraction-muxing
description: Engineering practices for media extraction using NewPipe Extractor, OkHttp chunked streaming download, and FFmpegKit audio/video muxing. Use when implementing or troubleshooting YouTube/TikTok video extraction, DASH adaptive streams, or FFmpeg commands.
---

# Stream Extraction, Streaming Download & FFmpeg Muxing Specialist

This skill guides the implementation and troubleshooting of the media processing pipeline in Personal Media Saver.

---

## 1. NewPipe Extractor Integration

Use `org.schabi.newpipe:NewPipeExtractor` as the primary engine for YouTube and TikTok.

### Initialization
```kotlin
NewPipe.init(DownloaderImpl.getInstance(okHttpClient))
```
- Wrap `DownloaderImpl` using the shared OkHttp singleton to maintain connection pooling and timeout settings.

### Stream Resolution Logic
- **YouTube 360p / 720p Progressive**:
  Contains both audio and video in a single stream (`streamInfo.videoStreams`). Can be downloaded directly without muxing.
- **YouTube 1080p+ & High-Bitrate (DASH Adaptive)**:
  Video stream (`VideoStream`) contains NO audio track.
  Audio stream (`AudioStream`) must be fetched separately.
  Both streams must be downloaded concurrently or sequentially, then combined via FFmpeg.

---

## 2. OkHttp Streaming Download Engine

Never buffer large media files in memory (`byte[]` or `response.body.bytes()`). Always stream chunks to disk.

```kotlin
suspend fun downloadToFile(
    call: Call,
    destination: File,
    onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
) = withContext(Dispatchers.IO) {
    call.execute().use { response ->
        if (!response.isSuccessful) throw AppError.DownloadFailed(response.code)
        val body = response.body ?: throw AppError.DownloadFailed(null)
        val totalBytes = body.contentLength()

        destination.outputStream().use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    coroutineContext.ensureActive() // Check for cancellation
                    output.write(buffer, 0, read)
                    bytesRead += read
                    onProgress(bytesRead, totalBytes)
                }
            }
        }
    }
}
```

---

## 3. FFmpegKit Audio+Video Muxing

Combine separate audio and video streams into a standardized `.mp4` container without re-encoding (copy codecs for speed):

```kotlin
suspend fun muxStreams(
    videoFile: File,
    audioFile: File,
    outputFile: File
): Result<Unit> = withContext(Dispatchers.Default) {
    val cmd = "-y -i \"${videoFile.absolutePath}\" -i \"${audioFile.absolutePath}\" -c:v copy -c:a copy \"${outputFile.absolutePath}\""
    val session = FFmpegKit.execute(cmd)
    if (ReturnCode.isSuccess(session.returnCode)) {
        videoFile.delete()
        audioFile.delete()
        Result.success(Unit)
    } else {
        outputFile.delete()
        Result.failure(AppError.MuxingFailed(Exception(session.failStackTrace)))
    }
}
```

### ABI Split Strategy
To prevent excessive APK size from FFmpeg native libraries:
- Configure Gradle ABI split to only bundle `arm64-v8a` (and optionally `armeabi-v7a` or `x86_64` for emulator testing).
- Never bundle all ABIs into a universal APK unless debugging.
