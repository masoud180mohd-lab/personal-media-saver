package com.mediasaver.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room database entity storing available stream options for a given download item.
 */
@Entity(
    tableName = "stream_options",
    foreignKeys = [
        ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["downloadId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["downloadId"])
    ]
)
data class StreamOptionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "downloadId")
    val downloadId: String,

    @ColumnInfo(name = "streamUrl")
    val streamUrl: String,

    @ColumnInfo(name = "quality")
    val quality: String? = null,

    @ColumnInfo(name = "format")
    val format: String? = null,

    @ColumnInfo(name = "mimeType")
    val mimeType: String? = null,

    @ColumnInfo(name = "isVideoOnly")
    val isVideoOnly: Boolean = false,

    @ColumnInfo(name = "isAudioOnly")
    val isAudioOnly: Boolean = false,

    @ColumnInfo(name = "fileSize")
    val fileSize: Long? = null,

    @ColumnInfo(name = "bitrate")
    val bitrate: Int? = null,

    @ColumnInfo(name = "codec")
    val codec: String? = null
)
