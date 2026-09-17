package com.mediasaver.core.storage

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages creation, safety checks, and garbage collection of temporary files
 * in the application's private cache directory.
 */
@Singleton
class TempFileManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val downloadsDir: File
        get() = File(context.cacheDir, "downloads").apply {
            if (!exists()) mkdirs()
        }

    /**
     * Creates a unique temporary file for an in-progress download task.
     *
     * @param downloadId The unique task ID
     * @param suffix Optional suffix (e.g. "_video.tmp", "_audio.tmp", ".tmp")
     */
    fun createTempDownloadFile(downloadId: String, suffix: String = ".tmp"): File {
        return File(downloadsDir, "${downloadId}${suffix}")
    }

    /**
     * Deletes a temporary file safely without throwing exceptions.
     */
    fun deleteTempFile(file: File?): Boolean {
        return try {
            file?.takeIf { it.exists() }?.delete() ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if the device has sufficient internal storage space available.
     *
     * @param requiredBytes Bytes needed for download/muxing
     * @param safetyMarginBytes Additional buffer to prevent filling disk completely (default: 50 MB)
     * @return True if sufficient space is available
     */
    fun hasAvailableStorage(requiredBytes: Long, safetyMarginBytes: Long = 50 * 1024 * 1024L): Boolean {
        return getAvailableStorageBytes() >= (requiredBytes + safetyMarginBytes)
    }

    /**
     * Returns the available bytes in the cache directory.
     */
    fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(context.cacheDir.path)
            stat.availableBytes
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    /**
     * Scans the downloads cache directory and deletes orphaned temporary files
     * older than [maxAgeMillis] (default 24 hours).
     *
     * @return Count of deleted orphaned files
     */
    fun cleanOrphanedTempFiles(maxAgeMillis: Long = 24 * 60 * 60 * 1000L): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        var deletedCount = 0
        downloadsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }
}
