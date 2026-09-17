package com.mediasaver.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mediasaver.core.database.entity.StreamOptionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for stream options associated with download tasks.
 */
@Dao
interface StreamOptionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(options: List<StreamOptionEntity>)

    @Query("SELECT * FROM stream_options WHERE downloadId = :downloadId")
    suspend fun getByDownloadId(downloadId: String): List<StreamOptionEntity>

    @Query("SELECT * FROM stream_options WHERE downloadId = :downloadId")
    fun observeByDownloadId(downloadId: String): Flow<List<StreamOptionEntity>>

    @Query("DELETE FROM stream_options WHERE downloadId = :downloadId")
    suspend fun deleteByDownloadId(downloadId: String)
}
