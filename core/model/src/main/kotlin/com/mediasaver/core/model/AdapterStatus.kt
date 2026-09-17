package com.mediasaver.core.model

/**
 * Health and operational status of an external platform extractor.
 */
enum class AdapterStatus {
    /** Fully functional, streams resolving properly. */
    OPERATIONAL,

    /** Some streams or qualities failing, but partial extraction works. */
    DEGRADED,

    /** Platform has updated obfuscation/anti-bot; extraction currently failing. */
    BROKEN,

    /** Health state has not yet been determined. */
    UNKNOWN
}
