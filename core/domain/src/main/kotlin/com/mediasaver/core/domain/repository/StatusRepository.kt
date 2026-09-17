package com.mediasaver.core.domain.repository

import com.mediasaver.core.model.WhatsAppStatus
import kotlinx.coroutines.flow.Flow

/**
 * Domain repository interface for managing, scanning, and saving WhatsApp statuses.
 */
interface StatusRepository {

    /**
     * Observes all indexed WhatsApp status items from local database.
     */
    fun observeStatuses(): Flow<List<WhatsAppStatus>>

    /**
     * Checks if SAF permission has been granted for the requested WhatsApp client.
     */
    suspend fun isAccessGranted(isBusiness: Boolean = false): Boolean

    /**
     * Scans the granted SAF directory tree for available status media files.
     */
    suspend fun scanStatuses(isBusiness: Boolean = false): Result<List<WhatsAppStatus>>

    /**
     * Saves a chosen status file to the public MediaStore gallery.
     *
     * @param status The status item to copy
     * @param overwrite If true, saves even if marked as duplicate
     * @return Result containing the saved MediaStore URI string
     */
    suspend fun saveStatus(status: WhatsAppStatus, overwrite: Boolean = false): Result<String>
}
