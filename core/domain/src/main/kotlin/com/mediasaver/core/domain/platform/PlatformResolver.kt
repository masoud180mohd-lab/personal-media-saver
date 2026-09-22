package com.mediasaver.core.domain.platform

import com.mediasaver.core.model.PlatformId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes an incoming media URL to the appropriate [PlatformAdapter].
 * Completely decoupled from platform-specific regex or domain logic;
 * queries each registered adapter via [PlatformAdapter.canHandle].
 */
@Singleton
class PlatformResolver @Inject constructor(
    private val adapters: @JvmSuppressWildcards List<PlatformAdapter>
) {

    /**
     * Resolves the matching [PlatformAdapter] that can handle the given URL.
     * Returns null if no registered adapter can handle the URL.
     *
     * @param url The raw or normalized URL to match
     * @return Matching [PlatformAdapter] or null if unsupported
     */
    fun resolve(url: String): PlatformAdapter? {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return null
        return adapters.firstOrNull { it.canHandle(cleanUrl) }
    }

    /**
     * Retrieves an adapter by its unique [PlatformId].
     */
    fun getAdapter(platformId: PlatformId): PlatformAdapter? {
        return adapters.firstOrNull { it.platformId == platformId }
    }

    /**
     * Returns all registered adapters.
     */
    fun getAllAdapters(): List<PlatformAdapter> = adapters
}
