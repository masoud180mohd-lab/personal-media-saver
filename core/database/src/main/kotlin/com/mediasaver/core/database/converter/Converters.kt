package com.mediasaver.core.database.converter

import androidx.room.TypeConverter
import com.mediasaver.core.model.DownloadStatus
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.ThemeMode

/**
 * Room type converters for domain enums.
 */
class Converters {

    @TypeConverter
    fun fromPlatformId(platformId: PlatformId?): String? {
        return platformId?.name
    }

    @TypeConverter
    fun toPlatformId(value: String?): PlatformId? {
        return value?.let { PlatformId.fromString(it) }
    }

    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun toDownloadStatus(value: String?): DownloadStatus? {
        return value?.let {
            runCatching { DownloadStatus.valueOf(it) }.getOrDefault(DownloadStatus.FAILED)
        }
    }

    @TypeConverter
    fun fromThemeMode(themeMode: ThemeMode?): String? {
        return themeMode?.name
    }

    @TypeConverter
    fun toThemeMode(value: String?): ThemeMode? {
        return value?.let {
            runCatching { ThemeMode.valueOf(it) }.getOrDefault(ThemeMode.SYSTEM)
        }
    }
}
