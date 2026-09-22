package com.mediasaver.core.common

/**
 * Privacy-first logging utility enforcing the strict Zero-Telemetry policy:
 * - Debug logs are emitted ONLY when [isDebug] is true (debug builds).
 * - Release builds discard all log events immediately.
 * - No data, breadcrumbs, or device identifiers are ever forwarded to remote services.
 * - Decoupled from direct Android framework dependencies to maintain pure JVM compatibility.
 */
object AppLogger {

    @Volatile
    var isDebug: Boolean = false

    var loggerDelegate: ((priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit)? = null

    fun d(tag: String, message: String) = log(3, tag, message, null)
    fun i(tag: String, message: String) = log(4, tag, message, null)
    fun w(tag: String, message: String) = log(5, tag, message, null)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(6, tag, message, throwable)

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        if (!isDebug) return

        val delegate = loggerDelegate
        if (delegate != null) {
            delegate(priority, tag, message, throwable)
            return
        }

        // Reflection fallback to android.util.Log if available on Android runtime
        val handled = runCatching {
            val logClass = Class.forName("android.util.Log")
            when (priority) {
                3 -> logClass.getMethod("d", String::class.java, String::class.java).invoke(null, tag, message)
                4 -> logClass.getMethod("i", String::class.java, String::class.java).invoke(null, tag, message)
                5 -> logClass.getMethod("w", String::class.java, String::class.java).invoke(null, tag, message)
                6 -> {
                    if (throwable != null) {
                        logClass.getMethod("e", String::class.java, String::class.java, Throwable::class.java).invoke(null, tag, message, throwable)
                    } else {
                        logClass.getMethod("e", String::class.java, String::class.java).invoke(null, tag, message)
                    }
                }
                else -> null
            }
            true
        }.getOrDefault(false)

        if (!handled) {
            val level = when (priority) {
                3 -> "DEBUG"
                4 -> "INFO"
                5 -> "WARN"
                6 -> "ERROR"
                else -> "LOG"
            }
            println("[$level] $tag: $message")
            throwable?.printStackTrace(System.out)
        }
    }
}
