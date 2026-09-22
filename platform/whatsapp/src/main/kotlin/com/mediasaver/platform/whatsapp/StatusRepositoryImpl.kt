package com.mediasaver.platform.whatsapp

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mediasaver.core.common.HashUtils
import com.mediasaver.core.database.dao.WhatsAppStatusDao
import com.mediasaver.core.domain.repository.SettingsRepository
import com.mediasaver.core.domain.repository.StatusRepository
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StatusMediaType
import com.mediasaver.core.model.WhatsAppStatus
import com.mediasaver.core.model.error.AppError
import com.mediasaver.core.storage.MediaStoreManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [StatusRepository] coordinating SAF scanning, Room caching,
 * duplicate detection via content hash, and Scoped Storage MediaStore saving.
 */
@Singleton
class StatusRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statusDao: WhatsAppStatusDao,
    private val scanner: StatusFileScanner,
    private val mapper: WhatsAppStatusMapper,
    private val mediaStoreManager: MediaStoreManager,
    private val settingsRepository: SettingsRepository
) : StatusRepository {

    override fun observeStatuses(): Flow<List<WhatsAppStatus>> {
        return statusDao.observeAll().map { entities ->
            entities.map { mapper.toDomain(it) }
        }
    }

    override suspend fun isAccessGranted(isBusiness: Boolean): Boolean = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSettings()
        val uriString = if (isBusiness) settings.whatsappBusinessStatusUri else settings.whatsappStatusUri
        Log.d("StatusRepo", "isAccessGranted: isBusiness=$isBusiness, uriString=$uriString")
        if (uriString.isNullOrBlank()) {
            Log.d("StatusRepo", "isAccessGranted: no URI saved, returning false")
            return@withContext false
        }

        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        val hasPerm = WhatsAppSafHelper.hasPersistedPermission(context.contentResolver, uri)
        Log.d("StatusRepo", "isAccessGranted: hasPersistedPermission=$hasPerm for uri=$uri")
        
        // If persisted permission check fails, also check if the URI is still in the persisted list
        if (!hasPerm && uri != null) {
            val allPermissions = context.contentResolver.persistedUriPermissions
            Log.d("StatusRepo", "All persisted URIs (${allPermissions.size}):")
            allPermissions.forEach {
                Log.d("StatusRepo", "  - ${it.uri} (read=${it.isReadPermission}, write=${it.isWritePermission})")
            }
        }
        hasPerm
    }

    override suspend fun scanStatuses(isBusiness: Boolean): Result<List<WhatsAppStatus>> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getSettings()
        val uriString = if (isBusiness) settings.whatsappBusinessStatusUri else settings.whatsappStatusUri
        if (uriString.isNullOrBlank()) {
            return@withContext Result.failure(
                AppError.PermissionDenied("Storage Access Framework folder access not granted.")
            )
        }

        val treeUri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return@withContext Result.failure(AppError.InvalidUrl(uriString))

        if (!WhatsAppSafHelper.hasPersistedPermission(context.contentResolver, treeUri)) {
            Log.w("StatusRepo", "scanStatuses: permission expired for $treeUri")
            return@withContext Result.failure(
                AppError.PermissionDenied("Folder access permission has expired or was revoked.")
            )
        }

        Log.d("StatusRepo", "scanStatuses: scanning directory $treeUri")
        val scannedItems = scanner.scanDirectory(treeUri)
        Log.d("StatusRepo", "scanStatuses: found ${scannedItems.size} items")
        val enrichedList = mutableListOf<WhatsAppStatus>()

        for (item in scannedItems) {
            val existing = statusDao.getByOriginalUri(item.uriString)
            if (existing != null) {
                enrichedList.add(item.copy(isSaved = existing.isSaved, savedPath = existing.savedPath, fileHash = existing.fileHash))
            } else {
                statusDao.insert(mapper.toEntity(item))
                enrichedList.add(item)
            }
        }

        Result.success(enrichedList)
    }

    override suspend fun saveStatus(status: WhatsAppStatus, overwrite: Boolean): Result<String> = withContext(Dispatchers.IO) {
        // 1. Check if already marked saved and overwrite is false
        if (!overwrite && status.isSaved && !status.savedPath.isNullOrBlank()) {
            return@withContext Result.success(status.savedPath!!)
        }

        val contentUri = runCatching { Uri.parse(status.uriString) }.getOrNull()
            ?: return@withContext Result.failure(AppError.InvalidUrl(status.uriString))

        val tempFile = File(context.cacheDir, "temp_status_${System.currentTimeMillis()}.${if (status.mediaType == StatusMediaType.VIDEO) "mp4" else "jpg"}")

        try {
            // 2. Stream from SAF ContentProvider to temporary cache file
            val inputStream = context.contentResolver.openInputStream(contentUri)
                ?: return@withContext Result.failure(AppError.MediaNotFound(status.uriString))

            tempFile.outputStream().use { output ->
                inputStream.use { input ->
                    input.copyTo(output)
                }
            }

            // 3. Compute SHA-256 content hash for duplicate detection
            val fileHash = HashUtils.sha256(tempFile)

            if (!overwrite) {
                val existingHash = statusDao.getByFileHash(fileHash)
                if (existingHash != null && existingHash.isSaved && !existingHash.savedPath.isNullOrBlank()) {
                    tempFile.delete()
                    return@withContext Result.success(existingHash.savedPath!!)
                }
            }

            // 4. Save to Scoped Storage MediaStore
            val savedUri = if (status.mediaType == StatusMediaType.VIDEO) {
                mediaStoreManager.saveVideo(
                    sourceFile = tempFile,
                    fileName = status.fileName,
                    platform = PlatformId.WHATSAPP
                )
            } else {
                mediaStoreManager.saveImage(
                    sourceFile = tempFile,
                    fileName = status.fileName,
                    platform = PlatformId.WHATSAPP
                )
            }.getOrThrow()

            tempFile.delete()

            // 5. Update status record in Room database
            val savedPathString = savedUri.toString()
            statusDao.markSaved(status.id, savedPathString)

            Result.success(savedPathString)
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(AppError.FileWriteFailed(cause = e))
        }
    }
}
