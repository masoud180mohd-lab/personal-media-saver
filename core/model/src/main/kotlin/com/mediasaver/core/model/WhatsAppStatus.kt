package com.mediasaver.core.model

/**
 * Enumeration of supported media types for WhatsApp Status items.
 */
enum class StatusMediaType {
    IMAGE,
    VIDEO
}

/**
 * Pure Kotlin domain model representing a local WhatsApp Status media file.
 */
data class WhatsAppStatus(
    val id: String,
    val fileName: String,
    val uriString: String,
    val mediaType: StatusMediaType,
    val fileSize: Long,
    val lastModified: Long,
    val fileHash: String? = null,
    val isSaved: Boolean = false,
    val savedPath: String? = null
)
