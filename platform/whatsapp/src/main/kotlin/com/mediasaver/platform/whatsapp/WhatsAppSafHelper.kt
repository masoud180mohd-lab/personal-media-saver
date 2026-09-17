package com.mediasaver.platform.whatsapp

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract

/**
 * Helper governing Storage Access Framework (SAF) URI paths, initial folder hints,
 * and persistent permission grants for WhatsApp and WhatsApp Business.
 */
object WhatsAppSafHelper {

    // Target folder paths across Android versions
    const val PATH_WHATSAPP_ANDROID_11 = "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"
    const val PATH_WHATSAPP_LEGACY = "WhatsApp/Media/.Statuses"

    const val PATH_BUSINESS_ANDROID_11 = "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"
    const val PATH_BUSINESS_LEGACY = "WhatsApp Business/Media/.Statuses"

    /**
     * Builds an [Intent] launching the system file picker to prompt user to grant folder access.
     */
    fun createOpenDocumentTreeIntent(isBusiness: Boolean = false): Intent {
        val targetPath = if (isBusiness) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) PATH_BUSINESS_ANDROID_11 else PATH_BUSINESS_LEGACY
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) PATH_WHATSAPP_ANDROID_11 else PATH_WHATSAPP_LEGACY
        }

        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )

            // Suggest the default folder location to simplify user navigation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val initialUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:$targetPath"
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
    }

    /**
     * Persists read permissions for the granted tree URI across system reboots.
     */
    fun takePersistablePermission(contentResolver: ContentResolver, uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Permission might already be held or granted without persistable flags
        }
    }

    /**
     * Verifies whether read access for the given URI is currently persisted and valid.
     */
    fun hasPersistedPermission(contentResolver: ContentResolver, uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission
            }
        } catch (_: Exception) {
            false
        }
    }
}
