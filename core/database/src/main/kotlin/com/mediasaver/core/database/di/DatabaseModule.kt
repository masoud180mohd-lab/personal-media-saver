package com.mediasaver.core.database.di

import android.content.Context
import androidx.room.Room
import com.mediasaver.core.database.MediaSaverDatabase
import com.mediasaver.core.database.dao.DownloadDao
import com.mediasaver.core.database.dao.StreamOptionDao
import com.mediasaver.core.database.dao.WhatsAppStatusDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideMediaSaverDatabase(
        @ApplicationContext context: Context
    ): MediaSaverDatabase {
        return Room.databaseBuilder(
            context,
            MediaSaverDatabase::class.java,
            MediaSaverDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideDownloadDao(database: MediaSaverDatabase): DownloadDao {
        return database.downloadDao()
    }

    @Provides
    fun provideStreamOptionDao(database: MediaSaverDatabase): StreamOptionDao {
        return database.streamOptionDao()
    }

    @Provides
    fun provideWhatsAppStatusDao(database: MediaSaverDatabase): WhatsAppStatusDao {
        return database.whatsappStatusDao()
    }
}
