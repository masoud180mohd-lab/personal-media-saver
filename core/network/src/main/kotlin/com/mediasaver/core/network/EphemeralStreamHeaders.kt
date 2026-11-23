package com.mediasaver.core.network

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volatile in-memory store for short-lived HTTP request headers (e.g. per-page CDN tokens)
 * bound strictly to the lifecycle of the extracted stream URL and never persisted to disk.
 */
@Singleton
class EphemeralStreamHeaders @Inject constructor() {

    private data class Entry(val createdAtNs: Long, val headers: Map<String, String>)

    private val entries = LinkedHashMap<String, Entry>()

    fun put(streamUrl: String?, headers: Map<String, String>) {
        if (streamUrl.isNullOrBlank() || headers.isEmpty()) return
        val sanitized = headers.filterKeys { it.isNotBlank() }.filterValues { it.isNotBlank() }
        if (sanitized.isEmpty()) return
        val now = System.nanoTime()
        synchronized(entries) {
            evictExpiredLocked(now)
            while (entries.size >= MAX_ENTRIES) {
                entries.remove(entries.keys.first())
            }
            entries[streamUrl] = Entry(now, sanitized)
        }
    }

    fun get(streamUrl: String): Map<String, String> {
        if (streamUrl.isBlank()) return emptyMap()
        val now = System.nanoTime()
        synchronized(entries) {
            evictExpiredLocked(now)
            return entries[streamUrl]?.headers ?: emptyMap()
        }
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
    }

    private fun evictExpiredLocked(nowNs: Long) {
        entries.entries.removeAll { nowNs - it.value.createdAtNs >= TTL_NS }
    }

    private companion object {
        const val MAX_ENTRIES = 32
        const val TTL_NS = 300_000_000_000L
    }
}
