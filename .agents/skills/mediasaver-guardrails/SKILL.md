---
name: mediasaver-guardrails
description: Non-negotiable architecture, privacy, security and error-handling rules for the Personal Media Saver Android repo. Invoke at the start of EVERY task in this repository.
---

# MediaSaver Guardrails

## Architecture absolutes
- Fully on-device. No backend, no cloud, no Firebase/analytics/crash reporting/ads.
- Modules: :app, :core:{common,model,domain,data,database,network,download,storage},
  :platform:{youtube,tiktok,whatsapp,instagram}, :feature:{home,download,history,status,settings}.
- New platform work goes ONLY in :platform:<name> implementing PlatformAdapter;
  register it in the PlatformResolver DI module.
- Presentation layer never touches Room/OkHttp directly; go through use cases/repositories.

## Privacy absolutes (NFR-3)
- NEVER persist platform cookies, session tokens, auth credentials, or extraction internals.
- Network requests only to the target platform of the current URL.
- Zero telemetry. Logs: debug builds only, tag `MediaSaver:<Module>`, stripped in release.
- NEVER log media content, browsing history, settings dumps, or file hashes in production.

## Security absolutes (NFR-2)
- HTTPS only; validate URLs (scheme, length <= 2048, no javascript/file/content schemes).
- Redirect chain max 5; block redirects to private/local IP ranges.
- Sanitize filenames; extension allowlist .mp4 .webm .jpg .jpeg .png .webp;
  validate MIME type after download.
- Max file size default 2 GB; write to cacheDir temp first, atomic move to MediaStore after success.

## Error & degradation absolutes
- All failures map to the sealed AppError hierarchy with documented user messages and retry policy.
- Platform breakage is a normal operating condition: surface AdapterStatus
  (OPERATIONAL / DEGRADED / BROKEN); never crash, never show raw stack traces to the user.
- Retry: exponential backoff 1s→32s, max 3 automatic retries; NO retry on 404/403/storage-full;
  longer backoff on 429.

## Storage absolutes
- Final files via MediaStore: Pictures/MediaSaver/<Platform>/ and Movies/MediaSaver/<Platform>/.
- Temp files in context.cacheDir/downloads/; delete on failure/cancel; orphan sweep at app start.
- Duplicates: URL+platform+mediaId pre-check; SHA-256 post-check; prompt user on duplicate.

## When in doubt
Prefer: graceful degradation > existing dependency > clever hack.
Ask before adding ANY new dependency or Room schema change.
