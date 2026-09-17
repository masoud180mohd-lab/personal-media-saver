---
name: android-saf-mediastore
description: Best practices and technical guidelines for Storage Access Framework (SAF) and MediaStore operations in Personal Media Saver. Use when working on WhatsApp status reading, scoped storage permissions, MediaStore insertions, and file hash duplicate detection.
---

# Android Storage Access Framework (SAF) & MediaStore Specialist

This skill provides verified, robust guidelines for handling file storage on Android 10+ (API 29 to 35+), adhering to strict Scoped Storage requirements without requesting dangerous `MANAGE_EXTERNAL_STORAGE` permissions.

---

## 1. WhatsApp Status Access via SAF (Android 11+)

Starting with Android 11 (API 30), direct file access to `/storage/emulated/0/WhatsApp/Media/.Statuses/` is blocked by Scoped Storage.

### Acquisition Flow
1. Prompt user to grant persistent folder access using `ActivityResultContracts.OpenDocumentTree()`.
2. WhatsApp primary path: `Android/media/com.whatsapp/WhatsApp/Media/.Statuses` (or legacy `WhatsApp/Media/.Statuses`).
3. WhatsApp Business path: `Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses`.
4. Persist permissions immediately in `onActivityResult`:
   ```kotlin
   context.contentResolver.takePersistableUriPermission(
       treeUri,
       Intent.FLAG_GRANT_READ_URI_PERMISSION
   )
   ```
5. Store the persisted `treeUri.toString()` in `DataStore`.

### Enumeration & Safety
- Use `DocumentFile.fromTreeUri(context, treeUri)` to query files (`.jpg`, `.mp4`).
- Compute SHA-256 hash from the `InputStream` to detect duplicates against `WhatsAppStatusEntity` before saving.
- Cache thumbnails in `context.cacheDir/thumbnails/` to avoid repeatedly reading large files through SAF ContentResolver.

---

## 2. Saving to Shared Media via MediaStore

Never write directly to external paths like `/sdcard/Pictures`. Use `MediaStore.Images` and `MediaStore.Video`.

### MediaStore Insertion Pattern
```kotlin
suspend fun saveVideoToGallery(
    context: Context,
    tempFile: File,
    fileName: String,
    platformSubfolder: String
): Uri? = withContext(Dispatchers.IO) {
    val relativePath = "Movies/MediaSaver/$platformSubfolder"
    val contentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
        put(MediaStore.Video.Media.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val collectionUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    val itemUri = resolver.insert(collectionUri, contentValues) ?: return@withContext null

    try {
        resolver.openOutputStream(itemUri)?.use { out ->
            tempFile.inputStream().use { input ->
                input.copyTo(out)
            }
        }
        contentValues.clear()
        contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(itemUri, contentValues, null, null)
        itemUri
    } catch (e: Exception) {
        resolver.delete(itemUri, null, null)
        throw e
    }
}
```

---

## 3. Temporary File Management & Cleanup
- Active downloads write to `context.cacheDir/downloads/<downloadId>.tmp`.
- If download or muxing fails, immediately delete the temporary file.
- Implement an app-start `WorkManager` or initialization cleaner that scans `context.cacheDir/downloads/` for files older than 24 hours and deletes orphans.
