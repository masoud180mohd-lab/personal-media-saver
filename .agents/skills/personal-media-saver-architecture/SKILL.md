---
name: personal-media-saver-architecture
description: Architectural guidelines, module structure, and coding standards for Personal Media Saver according to implementation_plan.md. Use when designing, refactoring, or implementing core modules, platform adapters, use cases, or dependency injection.
---

# Personal Media Saver Architecture Guide

This skill enforces strict adherence to the Clean Multi-Module Architecture specified in `implementation_plan.md`.

## 1. Multi-Module Topology & Dependency Rules

The project must strictly isolate concerns across 17 modular boundaries:

```
:app                      (Hosts navigation graph, Hilt Application, activities)
  ├── :feature:*          (Feature UI screens, ViewModels, Compose Material 3)
  ├── :platform:*         (Platform extraction adapters: youtube, tiktok, whatsapp, instagram)
  ├── :core:download      (Download queue, OkHttp streaming workers, FFmpegKit muxing, Foreground Service)
  ├── :core:storage       (MediaStore integration, SAF management, Temp files)
  ├── :core:database      (Room DB: DownloadEntity, WhatsAppStatusEntity, DAOs)
  ├── :core:data          (Repository implementations, DataStore preferences)
  ├── :core:domain        (Pure Kotlin UseCases, Repository interfaces - NO Android dependencies)
  ├── :core:model         (Pure Kotlin domain models, AppError, MediaInfo - NO Android dependencies)
  ├── :core:network       (OkHttp client singleton, network monitoring)
  └── :core:common        (Utilities: URL validation, filename sanitization, SHA-256 hashing)
```

### Critical Dependency Rules
- `:core:model` and `:core:domain` must contain **ZERO** Android framework dependencies (`android.*`). They must be pure Kotlin.
- `:feature:*` modules communicate exclusively with `:core:domain` via Use Cases and Flow/StateFlow.
- Feature modules must NOT directly depend on `:core:database`, `:core:data` implementations, or `:platform:*` adapters.
- All platform extraction logic belongs inside `:platform:<platform_name>` behind the `PlatformAdapter` interface.

---

## 2. Platform Adapter Pattern

All content extractors must implement the unified `PlatformAdapter` interface:

```kotlin
package com.mediasaver.platform.api

import com.mediasaver.core.model.AdapterStatus
import com.mediasaver.core.model.MediaInfo
import com.mediasaver.core.model.PlatformId
import com.mediasaver.core.model.StreamInfo

interface PlatformAdapter {
    val platformId: PlatformId
    val platformName: String

    fun canHandle(url: String): Boolean
    suspend fun extractMediaInfo(url: String): Result<MediaInfo>
    suspend fun resolveStreams(mediaId: String): Result<List<StreamInfo>>
    suspend fun healthCheck(): AdapterStatus
}
```

The `PlatformResolver` selects the matching adapter using `canHandle(url)`:
```kotlin
class PlatformResolver(private val adapters: Set<PlatformAdapter>) {
    fun resolve(url: String): PlatformAdapter? = adapters.firstOrNull { it.canHandle(url) }
}
```

---

## 3. Strict Error Handling Hierarchy

Never throw raw unhandled exceptions or expose internal stack traces to the user. Map all failures to the `AppError` sealed hierarchy:

```kotlin
sealed class AppError : Exception() {
    data class InvalidUrl(val url: String) : AppError()
    data class UnsupportedPlatform(val domain: String) : AppError()
    data class MediaNotFound(val url: String) : AppError()
    data class ExtractionFailed(val platform: PlatformId, override val cause: Throwable?) : AppError()
    data class ContentRestricted(val reason: String) : AppError()
    data class NetworkError(override val cause: Throwable?) : AppError()
    data class DownloadFailed(val httpCode: Int?) : AppError()
    data class DownloadTimeout(val url: String) : AppError()
    data class StorageFull(val requiredBytes: Long) : AppError()
    data class PermissionDenied(val permission: String) : AppError()
    data class FileWriteFailed(override val cause: Throwable?) : AppError()
    data class MuxingFailed(override val cause: Throwable?) : AppError()
    data class Unknown(override val cause: Throwable?) : AppError()
}
```

---

## 4. Privacy & Zero-Telemetry Contract
- **NO third-party telemetry, analytics, or remote crash reporting SDKs** (Zero Firebase Analytics, Zero Sentry, Zero Crashlytics).
- No cookies or authentication credentials persisted for external platforms.
- Logging only in `BuildConfig.DEBUG` builds via an internal wrapper tagging `MediaSaver:<Module>`.
