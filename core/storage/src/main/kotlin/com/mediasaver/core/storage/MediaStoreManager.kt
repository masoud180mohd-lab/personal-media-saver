package com.mediasaver.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mediasaver.core.common.FileNameSanitizer
import com.mediasaver.core.common.SecurityUtils
import com.mediasaver.core.model.AudioMetadata
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.error.AppError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages atomic insertion and storage of media files into the public MediaStore
 * according to Android Scoped Storage specifications (API 26 to 35+).
 */
@Singleton
class MediaStoreManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun saveVideo(
        sourceFile: File,
        fileName: String,
        platform: PlatformId,
        mimeType: String = "video/mp4",
        subfolder: String? = null
    ) = save(
        sourceFile, fileName, platform, mimeType, "Movies", "mp4",
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        subfolder = subfolder
    )

    suspend fun saveImage(
        sourceFile: File,
        fileName: String,
        platform: PlatformId,
        mimeType: String = "image/jpeg",
        subfolder: String? = null
    ) = save(
        sourceFile, fileName, platform, mimeType, "Pictures", "jpg",
        if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        subfolder = subfolder
    )

    suspend fun saveAudio(
        sourceFile: File,
        fileName: String,
        platform: PlatformId,
        mimeType: String = "audio/mp4",
        subfolder: String? = null,
        metadata: AudioMetadata? = null
    ): Result<Uri> {
        val cleanMime = mimeType.substringBefore(';').trim().lowercase()
        val primaryResult = save(
            sourceFile = sourceFile,
            fileName = fileName,
            platform = platform,
            mimeType = mimeType,
            directory = "Music",
            defaultExtension = "m4a",
            collectionUri = if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            },
            subfolder = subfolder,
            audioMetadata = metadata
        )
        val err = primaryResult.exceptionOrNull()
        val unsupportedMime = err is IllegalArgumentException || err?.cause is IllegalArgumentException
        if (primaryResult.isFailure && Build.VERSION.SDK_INT >= 29 && unsupportedMime) {
            return save(
                sourceFile = sourceFile,
                fileName = fileName,
                platform = platform,
                mimeType = cleanMime.ifBlank { "application/octet-stream" },
                directory = "Download",
                defaultExtension = File(fileName).extension.ifBlank { "m4a" },
                collectionUri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                subfolder = subfolder
            )
        }
        return primaryResult
    }

    /** Roll back a new gallery entry if recording its completion fails. */
    fun discard(uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }

    private suspend fun save(
        sourceFile: File,
        fileName: String,
        platform: PlatformId,
        mimeType: String,
        directory: String,
        defaultExtension: String,
        collectionUri: Uri,
        subfolder: String? = null,
        audioMetadata: AudioMetadata? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val fileObj = File(fileName)
            val safeExt = SecurityUtils.normalizeExtension(fileObj.extension, defaultExtension)
            val safeBaseName = FileNameSanitizer.sanitize(fileObj.nameWithoutExtension, fallback = "media")
            val sanitizedFileName = "$safeBaseName.$safeExt"

            val platformFolder = when (platform) {
                PlatformId.YOUTUBE -> "YouTube"
                PlatformId.TIKTOK -> "TikTok"
                PlatformId.WHATSAPP -> "WhatsApp"
                PlatformId.INSTAGRAM -> "Instagram"
                PlatformId.FACEBOOK -> "Facebook"
                PlatformId.TWITTER -> "X"
                PlatformId.PINTEREST -> "Pinterest"
                PlatformId.UNKNOWN -> "Downloads"
            }
            val safeSubfolder = subfolder
                ?.takeIf { it.isNotBlank() }
                ?.let { FileNameSanitizer.sanitize(it, fallback = "").take(60).trimEnd('.', '_', '-', ' ') }
                ?.takeIf { it.isNotBlank() }
            val relativePath = if (safeSubfolder != null) {
                "$directory/MediaSaver/$platformFolder/$safeSubfolder"
            } else {
                "$directory/MediaSaver/$platformFolder"
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sanitizedFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (directory == "Music" && audioMetadata != null) {
                    audioMetadata.title?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        put(MediaStore.Audio.AudioColumns.TITLE, it)
                    }
                    audioMetadata.artist?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        put(MediaStore.Audio.AudioColumns.ARTIST, it)
                    }
                    audioMetadata.album?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        put(MediaStore.Audio.AudioColumns.ALBUM, it)
                    }
                    audioMetadata.trackNumber?.takeIf { it > 0 }?.let {
                        put(MediaStore.Audio.AudioColumns.TRACK, it)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val itemUri = resolver.insert(collectionUri, contentValues)
                ?: throw AppError.FileWriteFailed(cause = Exception("MediaStore insertion returned null URI"))

            try {
                resolver.openOutputStream(itemUri)?.use { outStream ->
                    sourceFile.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                } ?: throw AppError.FileWriteFailed(cause = Exception("Could not open MediaStore output stream"))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    check(resolver.update(itemUri, contentValues, null, null) > 0) { "MediaStore publication failed" }
                }

                itemUri
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw AppError.FileWriteFailed(cause = e)
            }
        }
    }

}
