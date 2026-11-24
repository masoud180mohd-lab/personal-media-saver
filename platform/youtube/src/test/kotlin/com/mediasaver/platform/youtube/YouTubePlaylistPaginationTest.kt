package com.mediasaver.platform.youtube

import com.mediasaver.core.model.PlaylistItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class YouTubePlaylistPaginationTest {

    private fun fakeItem(index: Int): PlaylistItem {
        val id = "vid%08d".format(index)
        return PlaylistItem(
            videoId = id,
            title = "Lesson $index",
            author = "Instructor",
            durationSeconds = 600,
            url = "https://www.youtube.com/watch?v=$id"
        )
    }

    @Test
    fun `collectPaginatedPlaylistItems aggregates 120 items across 3 pages preserving order and deduplicating`() = runBlocking {
        val page1 = (1..50).map { fakeItem(it) }
        val page2 = listOf(fakeItem(50)) + (51..100).map { fakeItem(it) }
        val page3 = (101..120).map { fakeItem(it) }

        val result = YouTubeAdapter.collectPaginatedPlaylistItems(
            initialItems = page1,
            initialNextPage = "page-2",
            hasNextPage = { it != null },
            fetchNextPage = { token ->
                when (token) {
                    "page-2" -> page2 to "page-3"
                    "page-3" -> page3 to null
                    else -> emptyList<PlaylistItem>() to null
                }
            }
        )

        assertEquals(120, result.size)
        assertEquals("Lesson 1", result.first().title)
        assertEquals("Lesson 120", result.last().title)
        assertEquals(120, result.map { it.videoId }.distinct().size)
    }

    @Test
    fun `collectPaginatedPlaylistItems enforces maxItems and maxPages caps`() = runBlocking {
        val page1 = (1..40).map { fakeItem(it) }
        val page2 = (41..80).map { fakeItem(it) }
        val page3 = (81..120).map { fakeItem(it) }

        val cappedByPages = YouTubeAdapter.collectPaginatedPlaylistItems(
            initialItems = page1,
            initialNextPage = 2,
            hasNextPage = { it != null },
            fetchNextPage = { page ->
                when (page) {
                    2 -> page2 to 3
                    3 -> page3 to null
                    else -> emptyList<PlaylistItem>() to null
                }
            },
            maxItems = 500,
            maxPages = 2
        )
        assertEquals(80, cappedByPages.size)

        val cappedByItems = YouTubeAdapter.collectPaginatedPlaylistItems(
            initialItems = page1,
            initialNextPage = 2,
            hasNextPage = { it != null },
            fetchNextPage = { page ->
                when (page) {
                    2 -> page2 to 3
                    3 -> page3 to null
                    else -> emptyList<PlaylistItem>() to null
                }
            },
            maxItems = 65,
            maxPages = 10
        )
        assertEquals(65, cappedByItems.size)
        assertEquals("Lesson 65", cappedByItems.last().title)
    }

    @Test
    fun `collectPaginatedPlaylistItems degrades gracefully when page 2 throws IOException`() = runBlocking {
        val page1 = (1..30).map { fakeItem(it) }

        val result = YouTubeAdapter.collectPaginatedPlaylistItems(
            initialItems = page1,
            initialNextPage = "page-2",
            hasNextPage = { it != null },
            fetchNextPage = { throw IOException("Transient page fetch failure") }
        )

        assertEquals(30, result.size)
        assertEquals("Lesson 1", result.first().title)
        assertEquals("Lesson 30", result.last().title)
    }
}
