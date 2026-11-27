package com.mediasaver.core.ui.util

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val AUDIO_EXTENSIONS = setOf("m4a", "mp3", "aac", "ogg", "opus", "wav", "flac")
private val EMPTY_COVER = ByteArray(0)
private val embeddedCoverCache = LruCache<String, ByteArray>(24)

/**
 * Returns true when the given media metadata or file path represents an audio item.
 */
fun isAudioMedia(
    filePath: String?,
    mimeType: String?,
    selectedFormat: String?
): Boolean {
    if (mimeType?.trim()?.startsWith("audio/", ignoreCase = true) == true) return true
    val fmt = selectedFormat?.trim()?.lowercase()
    if (fmt != null && fmt in AUDIO_EXTENSIONS) return true

    val path = filePath?.trim()?.lowercase().orEmpty()
    if (path.isEmpty()) return false
    if ("/audio/" in path) return true

    val cleanPath = path.substringBefore('?').substringBefore('#')
    val ext = cleanPath.substringAfterLast('.', missingDelimiterValue = "")
    return ext in AUDIO_EXTENSIONS
}

/**
 * Extracts embedded artwork bytes from a local audio file or content URI synchronously,
 * caching both positive and negative lookups in an in-memory LRU cache.
 */
fun extractEmbeddedAudioCoverBytesSync(context: Context, filePath: String?): ByteArray? {
    val path = filePath?.takeIf { it.isNotBlank() } ?: return null
    embeddedCoverCache.get(path)?.let { cached ->
        return cached.takeIf { it.isNotEmpty() }
    }

    val retriever = MediaMetadataRetriever()
    val extracted = try {
        when {
            path.startsWith("content://") || path.startsWith("file://") -> {
                retriever.setDataSource(context, Uri.parse(path))
            }
            else -> {
                val file = File(path)
                if (!file.exists()) {
                    embeddedCoverCache.put(path, EMPTY_COVER)
                    return null
                }
                retriever.setDataSource(file.path)
            }
        }
        retriever.embeddedPicture?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }

    embeddedCoverCache.put(path, extracted ?: EMPTY_COVER)
    return extracted
}

/**
 * Extracts embedded artwork bytes from a local audio file or content URI off the main thread.
 */
suspend fun loadEmbeddedAudioCoverBytes(context: Context, filePath: String?): ByteArray? =
    withContext(Dispatchers.IO) {
        extractEmbeddedAudioCoverBytesSync(context, filePath)
    }

/**
 * Resolves a Coil-loadable artwork model for audio, video, or image items.
 * For audio files, prefers cached or retrieved embedded cover art (`covr`/APIC) and falls back to
 * [thumbnailUrl] rather than passing an unreadable audio URI directly to Coil.
 */
@Composable
fun rememberMediaCoverModel(
    filePath: String?,
    thumbnailUrl: String?,
    mimeType: String? = null,
    selectedFormat: String? = null
): Any? {
    val context = LocalContext.current
    val cleanThumb = thumbnailUrl?.takeIf { it.isNotBlank() }
    val isAudio = remember(filePath, mimeType, selectedFormat) {
        isAudioMedia(filePath = filePath, mimeType = mimeType, selectedFormat = selectedFormat)
    }

    val initialModel = remember(filePath, cleanThumb, isAudio) {
        if (isAudio) {
            val cached = filePath?.takeIf { it.isNotBlank() }?.let { path ->
                embeddedCoverCache.get(path)?.takeIf { it.isNotEmpty() }
            }
            cached ?: cleanThumb
        } else {
            resolveNonAudioCoverModel(filePath, cleanThumb)
        }
    }

    val model by produceState<Any?>(
        initialValue = initialModel,
        key1 = filePath,
        key2 = cleanThumb,
        key3 = isAudio
    ) {
        value = if (isAudio) {
            loadEmbeddedAudioCoverBytes(context, filePath) ?: cleanThumb
        } else {
            resolveNonAudioCoverModel(filePath, cleanThumb)
        }
    }
    return model
}

private fun resolveNonAudioCoverModel(filePath: String?, cleanThumb: String?): Any? {
    val path = filePath?.takeIf { it.isNotBlank() } ?: return cleanThumb
    return when {
        path.startsWith("content://") || path.startsWith("file://") -> Uri.parse(path)
        else -> File(path).takeIf { it.exists() } ?: cleanThumb
    }
}
