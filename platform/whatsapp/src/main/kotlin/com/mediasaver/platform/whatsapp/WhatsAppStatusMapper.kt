package com.mediasaver.platform.whatsapp

import com.mediasaver.core.database.entity.WhatsAppStatusEntity
import com.mediasaver.core.model.StatusMediaType
import com.mediasaver.core.model.WhatsAppStatus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps between Room database entities and domain models for WhatsApp Status items.
 */
@Singleton
class WhatsAppStatusMapper @Inject constructor() {

    fun toEntity(domain: WhatsAppStatus): WhatsAppStatusEntity {
        return WhatsAppStatusEntity(
            id = domain.id,
            originalFileName = domain.fileName,
            originalUri = domain.uriString,
            mediaType = domain.mediaType.name,
            fileSize = domain.fileSize,
            lastModified = domain.lastModified,
            fileHash = domain.fileHash ?: "",
            isSaved = domain.isSaved,
            savedPath = domain.savedPath,
            discoveredAt = System.currentTimeMillis()
        )
    }

    fun toDomain(entity: WhatsAppStatusEntity): WhatsAppStatus {
        val mediaType = runCatching {
            StatusMediaType.valueOf(entity.mediaType.uppercase())
        }.getOrDefault(StatusMediaType.IMAGE)

        return WhatsAppStatus(
            id = entity.id,
            fileName = entity.originalFileName,
            uriString = entity.originalUri,
            mediaType = mediaType,
            fileSize = entity.fileSize,
            lastModified = entity.lastModified,
            fileHash = entity.fileHash.takeIf { it.isNotBlank() },
            isSaved = entity.isSaved,
            savedPath = entity.savedPath
        )
    }
}
