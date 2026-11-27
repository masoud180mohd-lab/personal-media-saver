package com.mediasaver.feature.history.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PlaybackPositionStoreTest {

    private var clockMs = 1_000L
    private lateinit var store: SharedPreferencesPlaybackPositionStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("test_playback_positions", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = SharedPreferencesPlaybackPositionStore(
            prefs = prefs,
            nowProvider = { clockMs },
            maxEntries = 3
        )
    }

    @Test
    fun `saves valid resume position ignores short starts and resets near completion`() {
        store.savePositionMs("item1", 2_500L, 120_000L)
        assertEquals(0L, store.getPositionMs("item1"))

        store.savePositionMs("item1", 45_000L, 120_000L)
        assertEquals(45_000L, store.getPositionMs("item1"))

        store.savePositionMs("item1", 116_000L, 120_000L)
        assertEquals(0L, store.getPositionMs("item1"))
    }

    @Test
    fun `trims oldest positions beyond maxEntries and persists normalized speed`() {
        clockMs = 100L; store.savePositionMs("a", 10_000L, 60_000L)
        clockMs = 200L; store.savePositionMs("b", 20_000L, 60_000L)
        clockMs = 300L; store.savePositionMs("c", 30_000L, 60_000L)
        clockMs = 400L; store.savePositionMs("d", 40_000L, 60_000L)

        assertEquals(0L, store.getPositionMs("a"))
        assertEquals(20_000L, store.getPositionMs("b"))
        assertEquals(30_000L, store.getPositionMs("c"))
        assertEquals(40_000L, store.getPositionMs("d"))

        assertEquals(1.0f, store.getPlaybackSpeed(), 0.001f)
        store.savePlaybackSpeed(1.48f)
        assertEquals(1.5f, store.getPlaybackSpeed(), 0.001f)
    }
}
