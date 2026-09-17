---
trigger: always_on
---

# Personal Media Saver — Engineering & Architectural Rules

Always adhere to these core rules when writing code for this project:

1. **Source of Truth**:
   All module naming, interfaces, database schemas, and architectural boundaries must strictly follow [implementation_plan.md](file:///c:/Users/PM/project/app_downloader/implementation_plan.md).

2. **Clean Multi-Module Discipline**:
   - `:core:model` and `:core:domain` MUST NOT import `android.*` packages. They must remain pure Kotlin modules testable with standard JVM unit tests.
   - UI layers (`:feature:*`) interact with business logic ONLY through domain use cases, never direct repository implementations or database DAOs.

3. **Privacy & Security**:
   - Zero third-party telemetry, crashlytics, or analytics SDKs allowed.
   - All input URLs must be strictly sanitized and validated before network operations.
   - Network requests must strictly use HTTPS.

4. **Storage & Android Compatibility**:
   - Strictly comply with Scoped Storage (Android 10 to 15+).
   - Never use raw file paths for public gallery folders. Use `MediaStore` with `IS_PENDING` flag.
   - Access WhatsApp `.Statuses` exclusively through Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) with persisted URI permissions.

5. **Concurrency & Streaming**:
   - All network downloads must stream chunks via OkHttp into temporary disk cache files (`context.cacheDir/downloads/`). Never load entire videos into memory.
   - Respect coroutine cancellation across all background download tasks.
   - Adaptive streams (1080p+) must be combined via FFmpegKit using `-c:v copy -c:a copy` to avoid slow video re-encoding.
