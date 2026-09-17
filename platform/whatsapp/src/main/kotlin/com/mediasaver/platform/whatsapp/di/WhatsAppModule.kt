package com.mediasaver.platform.whatsapp.di

import com.mediasaver.core.domain.repository.StatusRepository
import com.mediasaver.platform.whatsapp.StatusRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding dependencies for the WhatsApp Status platform adapter.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WhatsAppModule {

    @Binds
    @Singleton
    abstract fun bindStatusRepository(impl: StatusRepositoryImpl): StatusRepository
}
