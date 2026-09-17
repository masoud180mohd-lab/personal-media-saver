package com.mediasaver.core.download.di

import com.mediasaver.core.domain.repository.DownloadRepository
import com.mediasaver.core.download.muxing.DefaultMuxingEngine
import com.mediasaver.core.download.muxing.MuxingEngine
import com.mediasaver.core.download.repository.DownloadRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding dependencies for the download engine.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {

    @Binds
    @Singleton
    abstract fun bindMuxingEngine(impl: DefaultMuxingEngine): MuxingEngine

    @Binds
    @Singleton
    abstract fun bindDownloadRepository(impl: DownloadRepositoryImpl): DownloadRepository
}
