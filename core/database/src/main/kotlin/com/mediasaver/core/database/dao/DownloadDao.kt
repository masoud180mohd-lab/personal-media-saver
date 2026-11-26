package com.mediasaver.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.mediasaver.core.database.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for active and historical download items.
 */
@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(downloads: List<DownloadEntity>)

    @Update
    suspend fun update(download: DownloadEntity)

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("UPDATE downloads SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE downloads 
        SET downloadedBytes = :downloadedBytes, 
            totalBytes = COALESCE(:totalBytes, totalBytes), 
            updatedAt = :updatedAt 
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE downloads 
        SET status = 'COMPLETED', 
            filePath = :filePath, 
            fileHash = :fileHash, 
            mimeType = COALESCE(:mimeType, mimeType), 
            completedAt = :completedAt, 
            updatedAt = :updatedAt 
        WHERE id = :id
        """
    )
    suspend fun markCompleted(
        id: String,
        filePath: String,
        fileHash: String? = null,
        mimeType: String? = null,
        completedAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query(
        """
        UPDATE downloads 
        SET status = 'FAILED', 
            errorType = :errorType, 
            errorMessage = :errorMessage, 
            updatedAt = :updatedAt 
        WHERE id = :id
        """
    )
    suspend fun markFailed(
        id: String,
        errorType: String?,
        errorMessage: String?,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE downloads SET status = 'CANCELLED', updatedAt = :updatedAt WHERE id = :id")
    suspend fun markCancelled(id: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE downloads SET status = 'PAUSED', updatedAt = :updatedAt WHERE id = :id")
    suspend fun markPaused(id: String, updatedAt: Long = System.currentTimeMillis())

    @Transaction
    suspend fun completeWithProgress(
        id: String,
        path: String,
        hash: String,
        size: Long,
        mimeType: String? = null
    ) {
        updateProgress(id, size, size)
        markCompleted(id, path, hash, mimeType)
    }

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED' OR status = 'FAILED' OR status = 'CANCELLED'")
    suspend fun clearHistory()

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    @Query("SELECT * FROM downloads WHERE url = :url AND platform = :platform AND status IN ('QUEUED', 'EXTRACTING', 'DOWNLOADING', 'MUXING', 'PAUSED', 'COMPLETED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun findByUrlAndPlatform(url: String, platform: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE mediaId = :mediaId AND platform = :platform AND status IN ('QUEUED', 'EXTRACTING', 'DOWNLOADING', 'MUXING', 'PAUSED', 'COMPLETED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun findByMediaIdAndPlatform(mediaId: String, platform: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE fileHash = :fileHash AND status = 'COMPLETED' LIMIT 1")
    suspend fun findByFileHash(fileHash: String): DownloadEntity?

    @Query("UPDATE downloads SET retryCount = :retryCount, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateRetryCount(id: String, retryCount: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'EXTRACTING', 'DOWNLOADING', 'MUXING') ORDER BY createdAt ASC")
    suspend fun findActiveDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'PAUSED' ORDER BY createdAt ASC")
    suspend fun findPausedDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'FAILED' ORDER BY createdAt ASC")
    suspend fun findFailedDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'EXTRACTING', 'DOWNLOADING', 'MUXING') ORDER BY createdAt ASC")
    fun observeActiveDownloads(): Flow<List<DownloadEntity>>
}
