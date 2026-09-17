package com.mediasaver.platform.whatsapp

import com.mediasaver.core.database.entity.WhatsAppStatusEntity
import com.mediasaver.core.model.StatusMediaType
import com.mediasaver.core.model.WhatsAppStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WhatsAppStatusMapperTest {

    private lateinit var mapper: WhatsAppStatusMapper

    @Before
    fun setUp() {
        mapper = WhatsAppStatusMapper()
    }

    @Test
    fun `toEntity correctly maps all domain fields to database entity`() {
        val domain = WhatsAppStatus(
            id = "wa_status_1",
            fileName = "status_video.mp4",
            uriString = "content://com.android.externalstorage.documents/document/status_video.mp4",
            mediaType = StatusMediaType.VIDEO,
            fileSize = 4096000L,
            lastModified = 1700000000000L,
            fileHash = "abcdef1234567890",
            isSaved = true,
            savedPath = "content://media/external/video/media/100"
        )

        val entity = mapper.toEntity(domain)

        assertEquals("wa_status_1", entity.id)
        assertEquals("status_video.mp4", entity.originalFileName)
        assertEquals("content://com.android.externalstorage.documents/document/status_video.mp4", entity.originalUri)
        assertEquals("VIDEO", entity.mediaType)
        assertEquals(4096000L, entity.fileSize)
        assertEquals(1700000000000L, entity.lastModified)
        assertEquals("abcdef1234567890", entity.fileHash)
        assertTrue(entity.isSaved)
        assertEquals("content://media/external/video/media/100", entity.savedPath)
    }

    @Test
    fun `toDomain correctly maps database entity to domain model`() {
        val entity = WhatsAppStatusEntity(
            id = "wa_status_image_1",
            originalFileName = "status_photo.jpg",
            originalUri = "content://com.android.externalstorage.documents/document/status_photo.jpg",
            mediaType = "IMAGE",
            fileSize = 1024000L,
            lastModified = 1700000000000L,
            fileHash = "hash999",
            isSaved = false,
            savedPath = null,
            discoveredAt = 1700000005000L
        )

        val domain = mapper.toDomain(entity)

        assertEquals("wa_status_image_1", domain.id)
        assertEquals("status_photo.jpg", domain.fileName)
        assertEquals(StatusMediaType.IMAGE, domain.mediaType)
        assertEquals(1024000L, domain.fileSize)
        assertEquals("hash999", domain.fileHash)
        assertFalse(domain.isSaved)
        assertNull(domain.savedPath)
    }

    @Test
    fun `toDomain falls back to IMAGE for malformed or unknown media type`() {
        val entity = WhatsAppStatusEntity(
            id = "wa_status_unknown",
            originalFileName = "file.unknown",
            originalUri = "content://documents/file.unknown",
            mediaType = "UNKNOWN_TYPE",
            fileSize = 500L,
            lastModified = 1700000000000L,
            fileHash = "",
            isSaved = false,
            savedPath = null
        )

        val domain = mapper.toDomain(entity)
        assertEquals(StatusMediaType.IMAGE, domain.mediaType)
    }
}
