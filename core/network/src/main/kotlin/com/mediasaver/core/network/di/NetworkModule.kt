package com.mediasaver.core.network.di

import com.mediasaver.core.network.interceptor.SsrfRedirectInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing HTTP network clients with streaming timeouts and privacy configuration.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideSsrfRedirectInterceptor(): SsrfRedirectInterceptor {
        return SsrfRedirectInterceptor()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(ssrfRedirectInterceptor: SsrfRedirectInterceptor): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // Disable default automatic redirects; interceptor verifies every hop against SSRF & hop limits
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(ssrfRedirectInterceptor)
            .retryOnConnectionFailure(true)
            .build()
    }
}
