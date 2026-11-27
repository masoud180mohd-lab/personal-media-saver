package com.mediasaver.feature.history.player

import android.content.Context
import android.content.SharedPreferences
import com.mediasaver.core.model.SUPPORTED_PLAYBACK_SPEEDS
import com.mediasaver.core.model.shouldResetSavedPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists resume positions and preferred speed for in-app playback.
 */
interface PlaybackPositionStore {
    fun getPositionMs(itemId: String): Long
    fun savePositionMs(itemId: String, positionMs: Long, durationMs: Long)
    fun clearPosition(itemId: String)
    fun getPlaybackSpeed(): Float
    fun savePlaybackSpeed(speed: Float)
}

@Singleton
class SharedPreferencesPlaybackPositionStore(
    private val prefs: SharedPreferences,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val maxEntries: Int = MAX_ENTRIES
) : PlaybackPositionStore {

    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    override fun getPositionMs(itemId: String): Long {
        if (itemId.isBlank()) return 0L
        return prefs.getLong("$POS_PREFIX$itemId", 0L).coerceAtLeast(0L)
    }

    override fun savePositionMs(itemId: String, positionMs: Long, durationMs: Long) {
        if (itemId.isBlank()) return
        if (positionMs < MIN_RESUME_MS || shouldResetSavedPosition(positionMs, durationMs)) {
            clearPosition(itemId)
            return
        }

        val editor = prefs.edit()
            .putLong("$POS_PREFIX$itemId", positionMs)
            .putLong("$TS_PREFIX$itemId", nowProvider())

        trimOldestIfNeeded(editor, keepId = itemId)
        editor.apply()
    }

    override fun clearPosition(itemId: String) {
        if (itemId.isBlank()) return
        prefs.edit()
            .remove("$POS_PREFIX$itemId")
            .remove("$TS_PREFIX$itemId")
            .apply()
    }

    override fun getPlaybackSpeed(): Float {
        val raw = prefs.getFloat(KEY_SPEED, 1.0f)
        return SUPPORTED_PLAYBACK_SPEEDS.minByOrNull { kotlin.math.abs(it - raw) } ?: 1.0f
    }

    override fun savePlaybackSpeed(speed: Float) {
        val normalized = SUPPORTED_PLAYBACK_SPEEDS.minByOrNull { kotlin.math.abs(it - speed) } ?: 1.0f
        prefs.edit().putFloat(KEY_SPEED, normalized).apply()
    }

    private fun trimOldestIfNeeded(editor: SharedPreferences.Editor, keepId: String) {
        val timestamps = prefs.all.entries
            .asSequence()
            .filter { it.key.startsWith(TS_PREFIX) }
            .mapNotNull { entry ->
                val id = entry.key.removePrefix(TS_PREFIX)
                val ts = (entry.value as? Number)?.toLong() ?: return@mapNotNull null
                if (id == keepId) null else id to ts
            }
            .sortedBy { it.second }
            .toList()

        val overflow = (timestamps.size + 1) - maxEntries
        if (overflow > 0) {
            for ((evictId, _) in timestamps.take(overflow)) {
                editor.remove("$POS_PREFIX$evictId")
                editor.remove("$TS_PREFIX$evictId")
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "playback_positions"
        private const val POS_PREFIX = "pos_"
        private const val TS_PREFIX = "ts_"
        private const val KEY_SPEED = "playback_speed"
        const val MIN_RESUME_MS = 3_000L
        const val MAX_ENTRIES = 200
    }
}
