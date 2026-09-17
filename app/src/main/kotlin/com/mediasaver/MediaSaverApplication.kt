package com.mediasaver

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Main Application entry point for Personal Media Saver.
 * Annotated with [@HiltAndroidApp] to initiate Hilt code generation and dependency injection graph.
 *
 * Adheres to the strict Zero-Telemetry contract: No analytics, crash reporting, or remote tracking.
 */
@HiltAndroidApp
class MediaSaverApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Initialization for local-only components (e.g. notification channels, temp file cleanup)
    }
}
