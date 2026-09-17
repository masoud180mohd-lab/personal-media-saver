package com.mediasaver.core.domain.platform

import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PlatformResolverTest {

    private class FakeAdapter(
        override val platformId: PlatformId,
        override val platformName: String,
        private val matchingDomains: List<String>
    ) : PlatformAdapter {
        override fun canHandle(url: String): Boolean {
            return matchingDomains.any { url.contains(it, ignoreCase = true) }
        }

        override suspend fun extractMediaInfo(url: String): Result<MediaInfo> {
            return Result.success(
                MediaInfo(
                    id = "test_id",
                    platform = platformId,
                    title = "Test Media",
                    sourceUrl = url
                )
            )
        }

        override suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>> {
            return Result.success(emptyList())
        }

        override suspend fun healthCheck(): AdapterStatus = AdapterStatus.OPERATIONAL
    }

    private lateinit var youTubeAdapter: PlatformAdapter
    private lateinit var tikTokAdapter: PlatformAdapter
    private lateinit var whatsAppAdapter: PlatformAdapter
    private lateinit var instagramAdapter: PlatformAdapter
    private lateinit var resolver: PlatformResolver

    @Before
    fun setUp() {
        youTubeAdapter = FakeAdapter(PlatformId.YOUTUBE, "YouTube", listOf("youtube.com", "youtu.be"))
        tikTokAdapter = FakeAdapter(PlatformId.TIKTOK, "TikTok", listOf("tiktok.com"))
        whatsAppAdapter = FakeAdapter(PlatformId.WHATSAPP, "WhatsApp", listOf("whatsapp://", "content://"))
        instagramAdapter = FakeAdapter(PlatformId.INSTAGRAM, "Instagram", listOf("instagram.com"))

        resolver = PlatformResolver(
            listOf(
                youTubeAdapter,
                tikTokAdapter,
                whatsAppAdapter,
                instagramAdapter
            )
        )
    }

    @Test
    fun `routes YouTube URL to YouTubeAdapter`() {
        val adapter = resolver.resolve("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertNotNull(adapter)
        assertEquals(PlatformId.YOUTUBE, adapter?.platformId)
        assertEquals("YouTube", adapter?.platformName)
    }

    @Test
    fun `routes TikTok URL to TikTokAdapter`() {
        val adapter = resolver.resolve("https://vm.tiktok.com/ZM8xABCDE/")
        assertNotNull(adapter)
        assertEquals(PlatformId.TIKTOK, adapter?.platformId)
    }

    @Test
    fun `routes Instagram URL to InstagramAdapter`() {
        val adapter = resolver.resolve("https://www.instagram.com/reel/Cxyz12345/")
        assertNotNull(adapter)
        assertEquals(PlatformId.INSTAGRAM, adapter?.platformId)
    }

    @Test
    fun `routes WhatsApp local URI to WhatsAppAdapter`() {
        val adapter = resolver.resolve("whatsapp://status/file123")
        assertNotNull(adapter)
        assertEquals(PlatformId.WHATSAPP, adapter?.platformId)
    }

    @Test
    fun `returns null for unsupported platform URL`() {
        val adapter = resolver.resolve("https://vimeo.com/123456")
        assertNull(adapter)
    }

    @Test
    fun `returns null for blank URL`() {
        assertNull(resolver.resolve(""))
        assertNull(resolver.resolve("   "))
    }

    @Test
    fun `getAdapter returns correct adapter by platform id`() {
        assertEquals(youTubeAdapter, resolver.getAdapter(PlatformId.YOUTUBE))
        assertEquals(tikTokAdapter, resolver.getAdapter(PlatformId.TIKTOK))
        assertNull(resolver.getAdapter(PlatformId.UNKNOWN))
    }

    @Test
    fun `getAllAdapters returns complete registered list`() {
        assertEquals(4, resolver.getAllAdapters().size)
    }

    @Test
    fun `empty adapter list returns null safely`() {
        val emptyResolver = PlatformResolver(emptyList())
        assertNull(emptyResolver.resolve("https://youtube.com/watch?v=123"))
    }
}
