# Personal Media Saver — Comprehensive MVP QA Checklist

This document provides the definitive, end-to-end Quality Assurance (QA) checklist for the **Personal Media Saver** MVP on Android (API 26 to 35+).

---

## Pre-Test Setup & Test Matrix

### Test Environment Requirements
- **Hardware Targets**:
  - Android 14 / 15 (Pixel or stock Android) — Scoped Storage & granular media permissions
  - Android 11 / 12 / 13 (Samsung OneUI / Xiaomi MIUI) — SAF directory grant & background service restrictions
  - Android 10 (Legacy baseline)
- **Prerequisites**:
  - App installed via `adb install -r app-debug.apk` or `app-release.apk`
  - Active internet connection (Wi-Fi and 4G/5G mobile data)
  - WhatsApp installed with active viewed statuses (or mock `.Statuses` folder with `.jpg` and `.mp4` files)
  - Standard Gallery / Photos app installed to verify MediaStore visibility

---

## Journey 1: App Launch & Permissions

### 1.1 Cold Start Performance (< 2 Seconds)
- **Test Steps**:
  1. Force stop Personal Media Saver (`adb shell am force-stop com.mediasaver`).
  2. Launch the app from the device launcher or run:
     ```bash
     adb shell am start-activity -W -n com.mediasaver/.MainActivity
     ```
  3. Measure the `TotalTime` reported by `am start-activity` and observe UI time-to-interactive.
- **Expected Result**:
  - Cold start completes in under 2000 ms.
  - Splash/surface renders smoothly; HomeScreen displays URL input box and detected platform chip immediately.
- **Pass/Fail Criteria**:
  - **PASS**: Total launch time < 2000 ms, zero stuttering or black flicker.
  - **FAIL**: Launch time > 2000 ms or Application Not Responding (ANR) dialog.

---

### 1.2 First Launch on Fresh Sandbox (No Crashes)
- **Test Steps**:
  1. Clear app data completely:
     ```bash
     adb shell pm clear com.mediasaver
     ```
  2. Launch the app for the first time.
  3. Verify Room database initialization (`media_saver.db`), DataStore default preference creation, and `downloads` cache directory creation.
- **Expected Result**:
  - App boots cleanly with empty state.
  - No uncaught exceptions in Logcat (`adb logcat -s MediaSaver:* AndroidRuntime:E`).
  - [StartupCleanupWorker](file:///c:/Users/PM/project/app_downloader/core/download/src/main/kotlin/com/mediasaver/core/download/work/StartupCleanupWorker.kt) enqueues successfully via WorkManager without crashing.
- **Pass/Fail Criteria**:
  - **PASS**: Clean launch with zero crashes, initial state initialized.
  - **FAIL**: Crash on boot (`SQLiteException`, `IllegalStateException`, `Hilt` injection error).

---

### 1.3 Notification & Media Permissions Prompt
- **Test Steps**:
  1. Install app on Android 13+ (API 33+).
  2. Launch app and observe permissions request.
  3. Deny notification permission once, then grant notification permission on next prompt or in App Settings.
- **Expected Result**:
  - `POST_NOTIFICATIONS` runtime prompt appears when initiating first download or during startup.
  - On denial, app continues to function with in-app progress updates (no crash).
  - On grant, foreground service notifications display active download progress bar and speed.
- **Pass/Fail Criteria**:
  - **PASS**: Permissions requested correctly without blocking core app usability.
  - **FAIL**: App crashes when permission is denied, or foreground service fails to start.

---

## Journey 2: Android Share Sheet Ingestion

### 2.1 Share YouTube Video URL from YouTube App
- **Test Steps**:
  1. Open the official YouTube app or browser.
  2. Open any public video (e.g., `https://www.youtube.com/watch?v=dQw4w9WgXcQ`).
  3. Tap **Share** -> Select **Personal Media Saver** (or Media Saver icon).
- **Expected Result**:
  - [ShareReceiverActivity](file:///c:/Users/PM/project/app_downloader/app/src/main/kotlin/com/mediasaver/ShareReceiverActivity.kt) receives `ACTION_SEND` intent with `text/plain`.
  - Transparent activity extracts clean URL, dispatches to [MainActivity](file:///c:/Users/PM/project/app_downloader/app/src/main/kotlin/com/mediasaver/MainActivity.kt), and finishes itself immediately.
  - App navigates directly to `HomeScreen`, auto-fills URL input, and triggers [ProcessUrlUseCase](file:///c:/Users/PM/project/app_downloader/core/domain/src/main/kotlin/com/mediasaver/core/domain/usecase/ProcessUrlUseCase.kt).
  - Media preview card appears with video thumbnail, title, duration, and platform chip set to **YouTube**.
- **Pass/Fail Criteria**:
  - **PASS**: Smooth transition from share sheet to media preview in under 3 seconds.
  - **FAIL**: Transparent activity hangs, URL fails to populate, or screen remains on previous tab.

---

### 2.2 Share TikTok Video URL from TikTok App
- **Test Steps**:
  1. Open the TikTok app or mobile browser.
  2. Tap **Share** on any public TikTok video -> Tap **Copy link** or share directly to **Media Saver**.
- **Expected Result**:
  - App intercepts short URL (e.g., `https://vm.tiktok.com/ZMxxxxxx/` or `https://vt.tiktok.com/xxxx/`).
  - Follows redirects up to 5 hops safely, identifies TikTok video ID.
  - Media preview card shows title/author, thumbnail, duration, and platform chip set to **TikTok**.
- **Pass/Fail Criteria**:
  - **PASS**: Short URL resolves to canonical URL; preview card loads correctly.
  - **FAIL**: Redirect loop, extraction fails, or app displays raw JSON/stack trace.

---

### 2.3 Share Invalid Text or Non-Media Text
- **Test Steps**:
  1. Open any text editor or browser.
  2. Select plain text (e.g., `"Meeting notes for Thursday morning"`) and share to **Media Saver**.
- **Expected Result**:
  - [ShareReceiverActivity](file:///c:/Users/PM/project/app_downloader/app/src/main/kotlin/com/mediasaver/ShareReceiverActivity.kt) regex finds no valid HTTP/HTTPS URL.
  - Activity closes cleanly without launching or interrupting [MainActivity](file:///c:/Users/PM/project/app_downloader/app/src/main/kotlin/com/mediasaver/MainActivity.kt).
- **Pass/Fail Criteria**:
  - **PASS**: Clean finish, no background crash.
  - **FAIL**: App opens with blank screen or crashes attempting to parse null URL.

---

## Journey 3: Manual URL Input & Validation

### 3.1 Standard YouTube Watch URL
- **Test Steps**:
  1. Open app -> Navigate to **Home** tab.
  2. Paste standard URL: `https://www.youtube.com/watch?v=aqz-KE-bpKQ`.
  3. Tap **Process URL** button (or keyboard Enter).
- **Expected Result**:
  - Platform indicator chip updates to **YouTube** instantaneously.
  - Media preview card renders thumbnail, video title, author, and available stream qualities (360p, 720p, 1080p).
- **Pass/Fail Criteria**:
  - **PASS**: Correct metadata and stream options displayed.
  - **FAIL**: Extraction fails or spinner loads indefinitely.

---

### 3.2 Shortened youtu.be URL
- **Test Steps**:
  1. Enter shortened URL: `https://youtu.be/aqz-KE-bpKQ`.
  2. Tap **Process URL**.
- **Expected Result**:
  - [YouTubeUrlParser](file:///c:/Users/PM/project/app_downloader/platform/youtube/src/main/kotlin/com/mediasaver/platform/youtube/parser/YouTubeUrlParser.kt) resolves video ID `aqz-KE-bpKQ`.
  - Preview card displays identical media details to the standard watch URL.
- **Pass/Fail Criteria**:
  - **PASS**: Validates, normalizes, and extracts successfully.
  - **FAIL**: Treated as invalid URL or unsupported format.

---

### 3.3 YouTube Shorts URL
- **Test Steps**:
  1. Enter Shorts URL: `https://www.youtube.com/shorts/3dZ_k0x0g_o`.
  2. Tap **Process URL**.
- **Expected Result**:
  - Extractor recognizes `/shorts/` path pattern.
  - Media details and streams extracted properly.
- **Pass/Fail Criteria**:
  - **PASS**: Shorts video recognized as YouTube platform and parsed.
  - **FAIL**: Parsing fails with "Invalid URL".

---

### 3.4 TikTok Short URLs (`vm.tiktok.com` / `vt.tiktok.com`)
- **Test Steps**:
  1. Enter `https://vm.tiktok.com/ZGeXYz123/` into input box.
  2. Tap **Process URL**.
- **Expected Result**:
  - Safe redirect chain resolves canonical web URL (max 5 redirects, SSRF protection active).
  - Web parser extracts title, author, thumbnail, and watermark-free MP4 stream.
- **Pass/Fail Criteria**:
  - **PASS**: Preview card rendered with direct downloadable stream.
  - **FAIL**: Failure to follow redirect or crash due to redirect exception.

---

### 3.5 Unsupported URL & Dangerous Schemes
- **Test Steps**:
  1. Enter unsupported web URL: `https://wikipedia.org/wiki/Kotlin`.
  2. Enter dangerous schemes: `javascript:alert(1)`, `file:///etc/hosts`, `content://media/external`.
  3. Enter URL exceeding 2048 characters.
- **Expected Result**:
  - For Wikipedia: Error banner displays `"This platform is not currently supported"`.
  - For dangerous schemes: Rejected instantly with `"Scheme is strictly prohibited"`.
  - For 2048+ char URL: Rejected with `"URL exceeds maximum allowed length"`.
  - Zero raw exception stack traces shown to user.
- **Pass/Fail Criteria**:
  - **PASS**: Friendly domain error messages shown via [AppError](file:///c:/Users/PM/project/app_downloader/core/model/src/main/kotlin/com/mediasaver/core/model/error/AppError.kt), app remains stable.
  - **FAIL**: Uncaught exception, app crash, or attempt to open local file schemes.

---

## Journey 4: YouTube Download Engine & Muxing

### 4.1 Download 360p Progressive Video
- **Test Steps**:
  1. Process YouTube URL -> Select **360p (MP4)** from format chips.
  2. Tap **Download**.
  3. App auto-navigates to **Downloads** tab.
- **Expected Result**:
  - Task state advances: `QUEUED` -> `DOWNLOADING` -> `COMPLETED`.
  - Single progressive stream downloaded directly via OkHttp chunked streaming to temporary cache (`cacheDir/downloads/<id>.tmp`).
  - On finish: Atomically moved to `Movies/MediaSaver/YouTube/<title>.mp4` via MediaStore.
  - Temporary file deleted from cache.
  - Badge counter on Downloads bottom nav updates and clears.
- **Pass/Fail Criteria**:
  - **PASS**: File playable in system gallery; temp files cleaned.
  - **FAIL**: Zero-byte file, download stalled, or temp file left in cache.

---

### 4.2 Download 720p Adaptive Stream with Muxing
- **Test Steps**:
  1. Process YouTube URL -> Select **720p (DASH Adaptive)**.
  2. Tap **Download**.
  3. Monitor task progress in Downloads screen.
- **Expected Result**:
  - Video stream downloaded to `<id>_vid.tmp`.
  - Audio stream (AAC/M4A) downloaded to `<id>_aud.tmp`.
  - State transitions to `MUXING`.
  - [MuxingEngine](file:///c:/Users/PM/project/app_downloader/core/download/src/main/kotlin/com/mediasaver/core/download/muxing/MuxingEngine.kt) invokes stream copy (`-c:v copy -c:a copy`) into `<id>.mp4`.
  - Atomically saved to `Movies/MediaSaver/YouTube/`.
  - Intermediate video and audio temp files deleted.
- **Pass/Fail Criteria**:
  - **PASS**: Output video plays with synchronized video and audio.
  - **FAIL**: Missing audio, muxing failure error, or uncleaned temp files.

---

### 4.3 Download 1080p High-Bitrate Video
- **Test Steps**:
  1. Process 1080p YouTube URL -> Select **1080p**.
  2. Tap **Download**.
- **Expected Result**:
  - Successfully downloads high-resolution DASH stream and audio stream.
  - Notification displays live speed (KB/s or MB/s), percent, and ETA.
  - Muxing finishes without OOM or re-encoding slowdown.
  - File saved to `Movies/MediaSaver/YouTube/`.
- **Pass/Fail Criteria**:
  - **PASS**: Full 1080p resolution verified in video metadata with crisp audio.
  - **FAIL**: Memory leak/OOM error or re-encoding delay (> 1 minute for muxing).

---

### 4.4 Cancel Download Mid-Stream
- **Test Steps**:
  1. Start a 1080p download.
  2. While progress is between 20% and 50%, tap **Cancel** in Downloads screen or notification action.
- **Expected Result**:
  - Coroutine job cancelled immediately.
  - Task status marked `CANCELLED` in Room database.
  - Temporary cache files (`.tmp`, `_vid.tmp`, `_aud.tmp`) deleted immediately.
  - Foreground notification dismissed.
  - No partial/corrupt files appear in the device gallery.
- **Pass/Fail Criteria**:
  - **PASS**: Clean cancellation, zero orphaned temp files, no gallery artifacts.
  - **FAIL**: Network download keeps running in background or corrupted file left in gallery.

---

### 4.5 Retry After Network Failure
- **Test Steps**:
  1. Start download -> Toggle Airplane Mode ON mid-stream.
  2. Verify download transitions to `FAILED` with retry option.
  3. Toggle Airplane Mode OFF.
  4. Tap **Retry** button on the failed task.
- **Expected Result**:
  - Task resets retry count and moves back to `QUEUED`.
  - [DownloadQueueManager](file:///c:/Users/PM/project/app_downloader/core/download/src/main/kotlin/com/mediasaver/core/download/queue/DownloadQueueManager.kt) re-executes task, downloads successfully, and transitions to `COMPLETED`.
- **Pass/Fail Criteria**:
  - **PASS**: Task recovers cleanly upon manual retry.
  - **FAIL**: Retry button unresponsive or infinite failure loop.

---

## Journey 5: TikTok Video Download

### 5.1 Download Public TikTok Video
- **Test Steps**:
  1. Paste public TikTok URL into Home screen -> Tap **Process URL**.
  2. Verify preview card -> Tap **Download**.
  3. Wait for download to finish.
- **Expected Result**:
  - Direct MP4 stream downloaded without watermark where available.
  - File stored in `Movies/MediaSaver/TikTok/`.
  - File entry created in Room `downloads` table with platform `TIKTOK`.
  - Video playable in Google Photos / Gallery.
- **Pass/Fail Criteria**:
  - **PASS**: File stored in `Movies/MediaSaver/TikTok/` and visible in gallery.
  - **FAIL**: Download fails or saved to incorrect directory.

---

## Journey 6: WhatsApp Status Browser & Saving

### 6.1 Grant SAF Directory Access
- **Test Steps**:
  1. Navigate to **Status** tab.
  2. If permission not granted, tap **Grant Folder Access**.
  3. System file picker (`ACTION_OPEN_DOCUMENT_TREE`) opens.
  4. Navigate to WhatsApp media directory (`Android/media/com.whatsapp/WhatsApp/Media/.Statuses` or `WhatsApp/Media/.Statuses`).
  5. Tap **Use this folder** -> Confirm **Allow**.
- **Expected Result**:
  - Persisted URI permission saved via `contentResolver.takePersistableUriPermission`.
  - Status browser updates state and displays grid of status images and videos.
  - SAF URI persisted in DataStore preferences.
- **Pass/Fail Criteria**:
  - **PASS**: Permission persists across app restarts without re-prompting.
  - **FAIL**: App asks for permission again after restart, or crash during SAF result callback.

---

### 6.2 Browse & Filter Statuses
- **Test Steps**:
  1. Open **Status** tab with granted permission.
  2. Toggle filters: **All**, **Images**, **Videos**.
  3. Toggle between standard WhatsApp and WhatsApp Business (if enabled).
- **Expected Result**:
  - Grid displays thumbnails with duration badges on video items.
  - Switching to "Images" shows only `.jpg`/`.png`.
  - Switching to "Videos" shows only `.mp4`.
  - Grid ordered by newest statuses first (`lastModified` descending).
- **Pass/Fail Criteria**:
  - **PASS**: Filtering is instant and responsive; media items render correctly.
  - **FAIL**: Video statuses missing, wrong media types displayed, or crash on empty folder.

---

### 6.3 Save Status Image & Video
- **Test Steps**:
  1. Select an image status -> Tap **Save**.
  2. Select a video status -> Tap **Save**.
- **Expected Result**:
  - Image copied to `Pictures/MediaSaver/WhatsApp/<filename>.jpg` via MediaStore.
  - Video copied to `Movies/MediaSaver/WhatsApp/<filename>.mp4` via MediaStore.
  - UI shows **Saved** badge with checkmark on both items.
  - [WhatsAppStatusEntity](file:///c:/Users/PM/project/app_downloader/core/database/src/main/kotlin/com/mediasaver/core/database/entity/WhatsAppStatusEntity.kt) updated with `isSaved = true`.
- **Pass/Fail Criteria**:
  - **PASS**: Images appear in Pictures, videos appear in Movies; saved status reflected in UI.
  - **FAIL**: Files saved to wrong directory or saved state lost on scroll.

---

### 6.4 Duplicate Status Detection
- **Test Steps**:
  1. Save a status file.
  2. Refresh status scan or reopen Status tab.
  3. Attempt to save the same status item again.
- **Expected Result**:
  - [StatusRepositoryImpl](file:///c:/Users/PM/project/app_downloader/platform/whatsapp/src/main/kotlin/com/mediasaver/platform/whatsapp/StatusRepositoryImpl.kt) compares SHA-256 hash or database URI.
  - Identifies file as already saved; snackbar indicates `"Status already saved"`.
  - No duplicate copies created in gallery.
- **Pass/Fail Criteria**:
  - **PASS**: Duplicate detected, no redundant file written to disk.
  - **FAIL**: Multiple duplicate files created with `(1)`, `(2)` suffixes in gallery.

---

## Journey 7: Download History Management

### 7.1 View History
- **Test Steps**:
  1. Navigate to **History** tab.
  2. Verify list of completed downloads.
- **Expected Result**:
  - Each item displays thumbnail, title, platform tag, completion date/time, and file size.
  - Empty state displayed gracefully when history is empty.
- **Pass/Fail Criteria**:
  - **PASS**: History items load reactively from Room database.
  - **FAIL**: Blank screen, missing thumbnails, or crash when loading empty history.

---

### 7.2 Open Downloaded File in External Player
- **Test Steps**:
  1. In History tab, tap an item or tap **Open**.
- **Expected Result**:
  - Fires `Intent(Intent.ACTION_VIEW)` with the MediaStore `content://` URI and MIME type.
  - System opens default video player (e.g., VLC, MX Player, Google Photos) and plays the media.
- **Pass/Fail Criteria**:
  - **PASS**: Media plays smoothly in external application.
  - **FAIL**: `FileUriExposedException` or "Unable to play video" error.

---

### 7.3 Share Downloaded File
- **Test Steps**:
  1. In History tab, tap **Share** icon on any completed item.
- **Expected Result**:
  - Android system Share Sheet opens with `Intent.ACTION_SEND` and `content://` URI.
  - Target apps (WhatsApp, Telegram, Drive) successfully receive and attach the file.
- **Pass/Fail Criteria**:
  - **PASS**: External apps can read and forward the shared file.
  - **FAIL**: Permission denial or empty attachment error in target app.

---

### 7.4 Delete Downloaded File
- **Test Steps**:
  1. In History tab, tap **Delete** on an item.
  2. Confirm deletion in the confirmation dialog.
- **Expected Result**:
  - Record removed from Room database `downloads` table.
  - File deleted from MediaStore gallery (`context.contentResolver.delete(uri)`).
  - Item animates out of History list immediately.
- **Pass/Fail Criteria**:
  - **PASS**: File removed from both database and physical device gallery.
  - **FAIL**: File remains in gallery or database record persists.

---

## Journey 8: Settings & Preferences

### 8.1 Change Preferred Quality
- **Test Steps**:
  1. Tap **Settings** gear icon in top bar.
  2. Change default quality preference (e.g., from 720p to 1080p).
  3. Navigate to Home tab -> Process a YouTube video.
- **Expected Result**:
  - Home screen pre-selects the preferred quality chip (1080p) automatically.
  - Setting persists across app restart.
- **Pass/Fail Criteria**:
  - **PASS**: Setting saved to DataStore and honored on Home screen.
  - **FAIL**: Preference reverts to default on app relaunch.

---

### 8.2 Change Max Concurrent Downloads
- **Test Steps**:
  1. In Settings, change **Max Concurrent Downloads** from 3 to 1.
  2. Enqueue 3 video downloads simultaneously.
- **Expected Result**:
  - First video enters `DOWNLOADING` state.
  - Videos 2 and 3 remain in `QUEUED` state.
  - When video 1 completes, video 2 automatically starts downloading.
- **Pass/Fail Criteria**:
  - **PASS**: Concurrency limit strictly enforced by [DownloadQueueManager](file:///c:/Users/PM/project/app_downloader/core/download/src/main/kotlin/com/mediasaver/core/download/queue/DownloadQueueManager.kt).
  - **FAIL**: All 3 download at the same time despite limit setting of 1.

---

### 8.3 Clear Download History
- **Test Steps**:
  1. In Settings, tap **Clear Download History**.
  2. Confirm in the dialog.
  3. Navigate to History tab.
- **Expected Result**:
  - History tab displays empty state.
  - Room database `downloads` records deleted.
  - Physical media files in `Movies/MediaSaver/` and `Pictures/MediaSaver/` remain intact on device.
- **Pass/Fail Criteria**:
  - **PASS**: History cleared, physical files preserved.
  - **FAIL**: History persists or physical user media files deleted without consent.

---

### 8.4 Re-grant WhatsApp Directory
- **Test Steps**:
  1. In Settings, tap **Change WhatsApp Folder**.
  2. Select another directory or re-select `.Statuses`.
- **Expected Result**:
  - System file picker opens; updated tree URI persisted in DataStore.
  - Status tab rescans and reflects the newly selected directory.
- **Pass/Fail Criteria**:
  - **PASS**: New folder successfully bound and saved.
  - **FAIL**: App crashes or ignores new folder selection.

---

## Journey 9: Edge Cases, Network Chaos & Recovery

### 9.1 No Internet at Extraction & Download
- **Test Steps**:
  1. Disable Wi-Fi and Cellular data (Airplane Mode ON).
  2. Enter YouTube URL -> Tap **Process URL**.
  3. Re-enable internet -> Tap **Retry**.
- **Expected Result**:
  - UI displays friendly error banner: `"Network connection failed. Please check your internet connectivity"`.
  - App does not freeze or crash.
  - Tapping Retry after internet restored loads media preview card normally.
- **Pass/Fail Criteria**:
  - **PASS**: Zero crash, clear user messaging, recovers on reconnect.
  - **FAIL**: Indefinite loading spinner or crash.

---

### 9.2 Very Slow / Throttled Internet (Socket Timeout)
- **Test Steps**:
  1. Throttle connection to 10 KB/s or simulate high latency via emulator / proxy.
  2. Start download.
- **Expected Result**:
  - Progress bar advances slowly; speed metric shows accurate low KB/s value.
  - If connection times out (> 60s read timeout), task fails cleanly with [AppError.DownloadTimeout](file:///c:/Users/PM/project/app_downloader/core/model/src/main/kotlin/com/mediasaver/core/model/error/AppError.kt).
  - [RetryPolicy](file:///c:/Users/PM/project/app_downloader/core/download/src/main/kotlin/com/mediasaver/core/download/retry/RetryPolicy.kt) triggers automatic exponential backoff retry.
- **Pass/Fail Criteria**:
  - **PASS**: Timeout handled without ANR; exponential retry kicks in.
  - **FAIL**: App hangs or foreground notification crashes.

---

### 9.3 Device Storage Low / Full
- **Test Steps**:
  1. Fill device storage until available space is < 50 MB.
  2. Attempt to download a 200 MB 1080p video.
- **Expected Result**:
  - [TempFileManager.hasAvailableStorage](file:///c:/Users/PM/project/app_downloader/core/storage/src/main/kotlin/com/mediasaver/core/storage/TempFileManager.kt) checks headroom prior to streaming.
  - Task immediately fails with `"Insufficient storage space available on the device"`.
  - No partial download started; no temp disk space consumed.
- **Pass/Fail Criteria**:
  - **PASS**: Pre-flight storage check blocks download safely.
  - **FAIL**: Download starts, crashes with unhandled `IOException: No space left on device`.

---

### 9.4 App Force-Killed During Active Download
- **Test Steps**:
  1. Start downloading a large 1080p video.
  2. At 40% progress, force-kill app (`adb shell am force-stop com.mediasaver`).
  3. Re-launch Personal Media Saver.
- **Expected Result**:
  - [StartupCleanupWorker](file:///c:/Users/PM/project/app_downloader/core/download/src/main/kotlin/com/mediasaver/core/download/work/StartupCleanupWorker.kt) runs via WorkManager.
  - [DownloadQueueManager.recoverQueueState](file:///c:/Users/PM/project/app_downloader/core/download/src/main/kotlin/com/mediasaver/core/download/queue/DownloadQueueManager.kt) recovers active tasks stuck in `DOWNLOADING` or `MUXING` back to `QUEUED`.
  - Incomplete temp files cleaned; download resumes or retries cleanly.
- **Pass/Fail Criteria**:
  - **PASS**: State recovered from Room DB; orphaned files purged; queue resumes.
  - **FAIL**: App crashes on restart or leaves orphaned gigabytes of temp files in cache.

---

### 9.5 Device Reboot During Active Download
- **Test Steps**:
  1. Start downloading a video.
  2. While downloading, restart the device (`adb reboot`).
  3. After boot completes, unlock device and launch Personal Media Saver.
- **Expected Result**:
  - Database maintains task record in `QUEUED` state.
  - User can tap **Retry** or app automatically resumes task when launched.
  - Corrupt partial files from pre-reboot are purged from cache.
- **Pass/Fail Criteria**:
  - **PASS**: Graceful recovery with zero corrupted files in gallery.
  - **FAIL**: Database corrupt or app crashes on boot.

---

### 9.6 Multiple Simultaneous Downloads Queue Stress
- **Test Steps**:
  1. In Settings, set max concurrency to 2.
  2. Rapidly enqueue 6 distinct videos (YouTube and TikTok).
- **Expected Result**:
  - Exactly 2 downloads execute in parallel in `DOWNLOADING` state.
  - Remaining 4 tasks remain in `QUEUED` state in FIFO order.
  - As each task completes, the next queued task begins immediately.
  - All 6 tasks eventually reach `COMPLETED` state.
- **Pass/Fail Criteria**:
  - **PASS**: All 6 files downloaded and saved correctly without race conditions.
  - **FAIL**: Tasks skip, deadlock, or crash due to SQLite concurrency lock.

---

### 9.7 Duplicate URL Submitted Twice
- **Test Steps**:
  1. Start downloading video URL `A`.
  2. While video `A` is downloading, paste URL `A` into Home screen and tap **Download** again.
- **Expected Result**:
  - [DuplicateDetector](file:///c:/Users/PM/project/app_downloader/core/download/src/main/kotlin/com/mediasaver/core/download/duplicate/DuplicateDetector.kt) detects that URL `A` is already active or completed.
  - Alert dialog warns: `"This media is already downloading / in your library"`.
  - User can choose to view existing item or force re-download.
- **Pass/Fail Criteria**:
  - **PASS**: Duplicate caught and communicated clearly to user.
  - **FAIL**: App starts duplicate redundant download blindly or overwrites existing file.

---

## 10. QA Sign-Off Summary Sheet

| Section | Journeys | Total Tests | Pass Target | Result |
|---|---|:---:|:---:|:---:|
| **Section 1** | App Launch & Permissions | 3 | 100% | `[ ] PASS / [ ] FAIL` |
| **Section 2** | Android Share Sheet | 3 | 100% | `[ ] PASS / [ ] FAIL` |
| **Section 3** | Manual URL Validation | 5 | 100% | `[ ] PASS / [ ] FAIL` |
| **Section 4** | YouTube Engine & Muxing | 5 | 100% | `[ ] PASS / [ ] FAIL` |
| **Section 5** | TikTok Video Download | 1 | 100% | `[ ] PASS / [ ] FAIL` |
| **Section 6** | WhatsApp Status SAF | 4 | 100% | `[ ] PASS / [ ] FAIL` |
| **Section 7** | Download History | 4 | 100% | `[ ] PASS / [ ] FAIL` |
| **Section 8** | Settings & Preferences | 4 | 100% | `[ ] PASS / [ ] FAIL` |
| **Section 9** | Edge Cases & Chaos | 7 | 100% | `[ ] PASS / [ ] FAIL` |
| **TOTAL** | **All MVP Journeys** | **36** | **100%** | **FINAL VERDICT: [ ] APPROVED** |
