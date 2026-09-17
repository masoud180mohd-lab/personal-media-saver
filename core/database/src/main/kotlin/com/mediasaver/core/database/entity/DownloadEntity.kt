package com.mediasaver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room database entity storing active and historical media download tasks.
 */
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["url", "platform"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class DownloadEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "platform")
    val platform: String,

    @ColumnInfo(name = "mediaId")
    val mediaId: String,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "thumbnailUrl")
    val thumbnailUrl: String? = null,

    @ColumnInfo(name = "thumbnailLocalPath")
    val thumbnailLocalPath: String? = null,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "errorType")
    val errorType: String? = null,

    @ColumnInfo(name = "errorMessage")
    val errorMessage: String? = null,

    @ColumnInfo(name = "selectedQuality")
    val selectedQuality: String? = null,

    @ColumnInfo(name = "selectedFormat")
    val selectedFormat: String? = null,

    @ColumnInfo(name = "totalBytes")
    val totalBytes: Long? = null,

    @ColumnInfo(name = "downloadedBytes")
    val downloadedBytes: Long = 0L,

    @ColumnInfo(name = "filePath")
    val filePath: String? = null,

    @ColumnInfo(name = "tempFilePath")
    val tempFilePath: String? = null,

    @ColumnInfo(name = "mimeType")
    val mimeType: String? = null,

    @ColumnInfo(name = "fileHash")
    val fileHash: String? = null,

    @ColumnInfo(name = "retryCount")
    val retryCount: Int = 0,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "completedAt")
    val completedAt: Long? = null,

    @ColumnInfo(name = "durationSeconds")
    val durationSeconds: Int? = null
)
