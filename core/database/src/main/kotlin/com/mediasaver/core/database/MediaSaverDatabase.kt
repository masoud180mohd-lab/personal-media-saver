package com.mediasaver.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mediasaver.core.database.converter.Converters
import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.database.dao.StreamOptionDao
import com.mediasaver.core.database.dao.WhatsAppStatusDao
import com.mediasaver.core.database.entity.DownloadEntity
import com.mediasaver.core.database.entity.StreamOptionEntity
import com.mediasaver.core.database.entity.WhatsAppStatusEntity

/**
 * Core Room database for Personal Media Saver.
 * Stores download states, stream options, and WhatsApp status metadata locally.
 */
@Database(
    entities = [
        DownloadEntity::class,
        StreamOptionEntity::class,
        WhatsAppStatusEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MediaSaverDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    abstract fun streamOptionDao(): StreamOptionDao

    abstract fun whatsappStatusDao(): WhatsAppStatusDao

    companion object {
        const val DATABASE_NAME = "media_saver.db"
    }
}
