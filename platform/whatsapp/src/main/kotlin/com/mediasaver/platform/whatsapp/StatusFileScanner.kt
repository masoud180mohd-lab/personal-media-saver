package com.mediasaver.platform.whatsapp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mediasaver.core.model.StatusMediaType
import com.mediasaver.core.model.WhatsAppStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans a granted Storage Access Framework (SAF) DocumentFile directory for WhatsApp media files.
 */
@Singleton
class StatusFileScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
        private val VIDEO_EXTENSIONS = setOf("mp4")
    }

    /**
     * Scans the directory identified by [treeUri] and enumerates available status files.
     *
     * @param treeUri The persisted SAF directory URI
     * @return Sorted list of [WhatsAppStatus] items (newest first)
     */
    suspend fun scanDirectory(treeUri: Uri): List<WhatsAppStatus> = withContext(Dispatchers.IO) {
        val rootDocument = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()
        if (!rootDocument.exists() || !rootDocument.isDirectory) return@withContext emptyList()

        val files = rootDocument.listFiles()
        val results = mutableListOf<WhatsAppStatus>()

        for (file in files) {
            val name = file.name ?: continue
            // Skip hidden files (.nomedia, hidden cache) and directories
            if (name.startsWith(".") || file.isDirectory) continue

            val extension = name.substringAfterLast('.', "").lowercase()
            val mimeType = file.type?.lowercase().orEmpty()

            val mediaType = when {
                IMAGE_EXTENSIONS.contains(extension) || mimeType.startsWith("image/") -> StatusMediaType.IMAGE
                VIDEO_EXTENSIONS.contains(extension) || mimeType.startsWith("video/") -> StatusMediaType.VIDEO
                else -> continue // Not a recognized media type
            }

            val size = file.length()
            if (size <= 0) continue // Skip 0-byte corrupt files

            val lastModified = file.lastModified()
            val id = "wa_${name}_${size}_$lastModified"

            results.add(
                WhatsAppStatus(
                    id = id,
                    fileName = name,
                    uriString = file.uri.toString(),
                    mediaType = mediaType,
                    fileSize = size,
                    lastModified = lastModified,
                    fileHash = null,
                    isSaved = false,
                    savedPath = null
                )
            )
        }

        // Sort descending by last modified (newest statuses at the top)
        results.sortByDescending { it.lastModified }
        results
    }
}
