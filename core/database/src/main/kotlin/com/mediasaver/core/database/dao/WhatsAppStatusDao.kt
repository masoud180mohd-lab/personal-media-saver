package com.mediasaver.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.mediasaver.core.database.entity.WhatsAppStatusEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for scanned WhatsApp status media items.
 */
@Dao
interface WhatsAppStatusDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(status: WhatsAppStatusEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(statuses: List<WhatsAppStatusEntity>)

    @Upsert
    suspend fun upsert(status: WhatsAppStatusEntity)

    @Query("SELECT * FROM whatsapp_statuses ORDER BY discoveredAt DESC")
    fun observeAll(): Flow<List<WhatsAppStatusEntity>>

    @Query("SELECT * FROM whatsapp_statuses WHERE originalUri = :originalUri LIMIT 1")
    suspend fun getByOriginalUri(originalUri: String): WhatsAppStatusEntity?

    @Query("SELECT * FROM whatsapp_statuses WHERE fileHash = :fileHash LIMIT 1")
    suspend fun getByFileHash(fileHash: String): WhatsAppStatusEntity?

    @Query("UPDATE whatsapp_statuses SET isSaved = 1, savedPath = :savedPath WHERE id = :id")
    suspend fun markSaved(id: String, savedPath: String)

    @Query("DELETE FROM whatsapp_statuses")
    suspend fun deleteAll()

    @Query("DELETE FROM whatsapp_statuses WHERE discoveredAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)
}
