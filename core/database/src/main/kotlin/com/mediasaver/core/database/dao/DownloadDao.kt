package com.mediasaver.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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
            completedAt = :completedAt, 
            updatedAt = :updatedAt 
        WHERE id = :id
        """
    )
    suspend fun markCompleted(
        id: String,
        filePath: String,
        fileHash: String? = null,
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

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED' OR status = 'FAILED' OR status = 'CANCELLED'")
    suspend fun clearHistory()

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    @Query("SELECT * FROM downloads WHERE url = :url AND platform = :platform ORDER BY createdAt DESC LIMIT 1")
    suspend fun findByUrlAndPlatform(url: String, platform: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'EXTRACTING', 'DOWNLOADING', 'MUXING') ORDER BY createdAt ASC")
    suspend fun findActiveDownloads(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'EXTRACTING', 'DOWNLOADING', 'MUXING') ORDER BY createdAt ASC")
    fun observeActiveDownloads(): Flow<List<DownloadEntity>>
}
