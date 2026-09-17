package com.mediasaver.core.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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

    /**
     * Saves a completed video file into the public Movies/MediaSaver directory.
     *
     * @param sourceFile The local completed file (e.g. in cache)
     * @param fileName The sanitized target file name
     * @param platform The originating platform
     * @param mimeType Target MIME type (default: video/mp4)
     * @return [Result] containing the final MediaStore URI
     */
    suspend fun saveVideo(
        sourceFile: File,
        fileName: String,
        platform: PlatformId,
        mimeType: String = "video/mp4"
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val platformFolder = when (platform) {
                PlatformId.YOUTUBE -> "YouTube"
                PlatformId.TIKTOK -> "TikTok"
                PlatformId.WHATSAPP -> "WhatsApp"
                PlatformId.INSTAGRAM -> "Instagram"
                PlatformId.UNKNOWN -> "Downloads"
            }
            val relativePath = "Movies/MediaSaver/$platformFolder"

            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

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
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)
                }

                itemUri
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw AppError.FileWriteFailed(cause = e)
            }
        }
    }

    /**
     * Saves a completed image file into the public Pictures/MediaSaver directory.
     */
    suspend fun saveImage(
        sourceFile: File,
        fileName: String,
        platform: PlatformId,
        mimeType: String = "image/jpeg"
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val platformFolder = when (platform) {
                PlatformId.WHATSAPP -> "WhatsApp"
                PlatformId.INSTAGRAM -> "Instagram"
                else -> "Downloads"
            }
            val relativePath = "Pictures/MediaSaver/$platformFolder"

            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

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
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(itemUri, contentValues, null, null)
                }

                itemUri
            } catch (e: Exception) {
                resolver.delete(itemUri, null, null)
                throw AppError.FileWriteFailed(cause = e)
            }
        }
    }
}
