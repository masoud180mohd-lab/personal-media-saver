package com.mediasaver.core.domain.platform

import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo

/**
 * Common abstraction implemented by all external platform adapters.
 * Isolates extraction mechanics, network protocols, and scraping logic
 * behind a unified domain contract.
 */
interface PlatformAdapter {
    val platformId: PlatformId
    val platformName: String

    /**
     * Check if this adapter can handle the given URL.
     */
    fun canHandle(url: String): Boolean

    /**
     * Extract media metadata from the specified URL.
     */
    suspend fun extractMediaInfo(url: String): Result<MediaInfo>

    /**
     * Resolve actual downloadable stream(s) for a given media content ID.
     */
    suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>>

    /**
     * Check whether this extractor is currently operational.
     */
    suspend fun healthCheck(): AdapterStatus
}
