# Personal Media Saver

A private, local-first, zero-backend Android media-saving utility built with modern Android architecture.

## Overview

Personal Media Saver allows saving media from multiple platforms into organized device storage with zero tracking and full privacy:
- **WhatsApp Status**: Local file access via Storage Access Framework (SAF) on Android 11+.
- **YouTube**: Stream extraction via NewPipe Extractor and adaptive stream muxing (FFmpegKit).
- **TikTok**: Public video extraction.
- **Instagram**: Public media extraction (V1).

## Architecture & Principles

- **100% On-Device**: Zero backend, zero cloud services, zero analytics, zero telemetry, no ads.
- **Clean Architecture**: 18 modular boundaries with pure Kotlin `:core:model` and `:core:domain`.
- **Platform Adapter Pattern**: Each platform extractor is isolated behind `PlatformAdapter`.
- **Process Death Resilience**: Downloads persist in Room database and run via Foreground Service (`dataSync`).
- **Scoped Storage Compliant**: Media saved via MediaStore with `IS_PENDING` flags (no raw filesystem paths).

## Tech Stack

- **Language**: Kotlin 2.1.0
- **UI**: Jetpack Compose, Material 3, Navigation Compose
- **Async**: Kotlin Coroutines, StateFlow
- **Storage**: Room 2.6.1, DataStore Preferences 1.1.1
- **Networking**: OkHttp 4.12.0 (streaming I/O)
- **Dependency Injection**: Hilt 2.54 with KSP
- **Image Loading**: Coil Compose 2.7.0
- **Background Work**: WorkManager 2.10.0

## Module Topology

```
:app                      (Application entry, Hilt setup, Navigation graph)
  ├── :feature:home       (Home screen, URL input)
  ├── :feature:download   (Active download progress & queue)
  ├── :feature:history    (Download history & preview)
  ├── :feature:status     (WhatsApp Status browser)
  ├── :feature:settings   (App preferences)
  ├── :platform:youtube   (YouTube extractor)
  ├── :platform:tiktok    (TikTok extractor)
  ├── :platform:whatsapp  (WhatsApp Status SAF scanner)
  ├── :platform:instagram (Instagram extractor)
  ├── :core:download      (Download engine, OkHttp streaming, muxing)
  ├── :core:storage       (MediaStore & SAF file management)
  ├── :core:database      (Room entities, DAOs, converters)
  ├── :core:data          (Repository implementations, DataStore)
  ├── :core:domain        (Pure Kotlin UseCases, repository contracts)
  ├── :core:model         (Pure Kotlin domain models, AppError)
  ├── :core:network       (OkHttp client provider)
  └── :core:common        (URL validation, hashing, filename sanitization)
```

## Security & Privacy

- HTTPS only for all network operations.
- Input URLs strictly sanitized and validated against length bounds (<= 2048 chars).
- No DRM bypass, no authentication storage, no access to private accounts.
