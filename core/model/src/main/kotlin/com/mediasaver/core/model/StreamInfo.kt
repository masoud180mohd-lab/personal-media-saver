package com.mediasaver.core.model

/**
 * Technical specification for a single downloadable media stream.
 */
data class StreamInfo(
    val url: String,
    val quality: String? = null,
    val format: String? = null,
    val mimeType: String? = null,
    val isVideoOnly: Boolean = false,
    val isAudioOnly: Boolean = false,
    val fileSize: Long? = null,
    val bitrate: Int? = null,
    val codec: String? = null
) {
    /** True if stream has both audio and video multiplexed natively. */
    val isProgressive: Boolean
        get() = !isVideoOnly && !isAudioOnly

    /** True if this is an adaptive stream requiring muxing. */
    val isAdaptive: Boolean
        get() = isVideoOnly || isAudioOnly
}
