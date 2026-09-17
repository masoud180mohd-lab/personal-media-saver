package com.mediasaver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room database entity tracking WhatsApp status files scanned via SAF.
 */
@Entity(
    tableName = "whatsapp_statuses",
    indices = [
        Index(value = ["originalUri"], unique = true),
        Index(value = ["fileHash"]),
        Index(value = ["discoveredAt"])
    ]
)
data class WhatsAppStatusEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "originalFileName")
    val originalFileName: String,

    @ColumnInfo(name = "originalUri")
    val originalUri: String,

    @ColumnInfo(name = "mediaType")
    val mediaType: String,

    @ColumnInfo(name = "fileSize")
    val fileSize: Long,

    @ColumnInfo(name = "lastModified")
    val lastModified: Long,

    @ColumnInfo(name = "fileHash")
    val fileHash: String,

    @ColumnInfo(name = "isSaved")
    val isSaved: Boolean = false,

    @ColumnInfo(name = "savedPath")
    val savedPath: String? = null,

    @ColumnInfo(name = "discoveredAt")
    val discoveredAt: Long = System.currentTimeMillis()
)
