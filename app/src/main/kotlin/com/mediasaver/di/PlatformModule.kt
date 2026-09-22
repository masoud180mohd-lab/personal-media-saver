package com.mediasaver.di

import com.mediasaver.core.domain.platform.PlatformAdapter
import com.mediasaver.core.domain.platform.PlatformResolver
import com.mediasaver.platform.instagram.InstagramAdapter
import com.mediasaver.platform.tiktok.TikTokAdapter
import com.mediasaver.platform.whatsapp.WhatsAppAdapter
import com.mediasaver.platform.youtube.YouTubeAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module responsible for registering and injecting [PlatformAdapter] implementations
 * and binding the centralized [PlatformResolver].
 */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides
    @Singleton
    fun providePlatformAdapters(
        youTubeAdapter: YouTubeAdapter,
        tikTokAdapter: TikTokAdapter,
        whatsAppAdapter: WhatsAppAdapter,
        instagramAdapter: InstagramAdapter
    ): @JvmSuppressWildcards List<PlatformAdapter> {
        return listOf(
            youTubeAdapter,
            tikTokAdapter,
            whatsAppAdapter,
            instagramAdapter
        )
    }
}
