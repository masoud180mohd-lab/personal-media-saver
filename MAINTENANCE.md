# Personal Media Saver - Build & Maintenance Guide

## 1. Build Instructions

To build Personal Media Saver, ensure you have Java 17+ installed.

### Debug Build
A debug build includes logging, testing tools, and is not code-obfuscated.
```bash
./gradlew assembleDebug
```
The APK will be output to: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build
A release build strips logs, shrinks code with R8/ProGuard, and restricts native libraries to `arm64-v8a` to minimize FFmpegKit size.
Before building, ensure you have set the signing credentials in your environment or local `.properties` file:
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

*(Ensure `keystore/release.jks` is placed in the `app/keystore/` directory).*

```bash
./gradlew assembleRelease
```
The APK will be output to: `app/build/outputs/apk/release/app-release.apk`

### Installing via ADB
To install the compiled debug APK directly to an attached device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
*Note: Use `-r` to replace an existing installation while keeping app data.*

---

## 2. Sideload & Update Procedure

Personal Media Saver is designed to be sideloaded (installed outside the Play Store) for maximum privacy.

### Updating Safely
To install an update over a previous APK without losing local history or settings:
1. Ensure the new APK is signed with the **exact same keystore** as the previously installed APK. Android's signature verification will reject updates with mismatched signatures.
2. Install the update using adb (`adb install -r <apk>`) or by transferring the APK to your phone and tapping to install via a file manager.

### When Extractor Libraries Update
If NewPipe Extractor pushes an update (e.g., to fix broken YouTube extraction):
1. Update the dependency version in `gradle/libs.versions.toml`.
2. Sync the project and run a quick local build.
3. Rapid testing for extraction:
   ```bash
   ./gradlew :platform:youtube:test --tests "com.mediasaver.platform.youtube.*"
   ```
4. Build the release APK and install over the existing app using the Sideload procedure above.

---

## 3. Maintenance Runbook

### Handling Extraction Failures (YouTube / TikTok)
Platforms constantly change their internal APIs, causing extraction to break.
- **Symptom**: `ExtractMediaInfo` or `ResolveStreams` returns a `DownloadException.ExtractionFailed` or `ContentRestricted`.
- **Action**: 
  1. Check if the upstream `NewPipe Extractor` repository has an open issue or a pending release fixing the issue.
  2. If an update is available, increment the version in `libs.versions.toml` and rebuild.
  3. If no update is available, you may need to temporarily mark the adapter as `PlatformHealthStatus.BROKEN` to prevent users from spamming failing downloads.

### WhatsApp Directory Changes
WhatsApp occasionally changes its SAF directory structures (e.g., moving to Android/media/com.whatsapp/...).
- **Symptom**: `StatusFileScanner` returns empty lists despite granting SAF permissions.
- **Action**: Prompt the user to re-select the WhatsApp status folder via the SAF Intent (`ACTION_OPEN_DOCUMENT_TREE`). The system handles resolving the actual physical path.

### Verifying Temp File Cleanup
- Temp files are stored in `context.cacheDir/downloads/`.
- Ensure no partial `.mp4` or `.temp` files remain if a download crashes. 
- You can inspect this directory on a non-rooted debug device using Android Studio's Device File Explorer: `data/data/com.mediasaver.debug/cache/downloads/`.

### Checking Adapter Health
- Use the `healthCheck()` function implemented in each `PlatformAdapter`. These can be surfaced to the UI to grey out features if an adapter is knowingly broken.

### Local Debug Logs
- Debug logging uses standard `Log.d` with tags prefixed like `MediaSaver:YouTubeAdapter`.
- Logs are strictly **stripped in release builds** by ProGuard (`-assumenosideeffects class android.util.Log`).

---

## 4. Observability & Privacy Rules

- **Zero-Telemetry**: Never add Crashlytics, Firebase Analytics, or Mixpanel.
- **Data Safety**: Never log the contents of users' MediaStore exports, URLs they paste, or their local network IP.
- **Log Stripping**: The `app/proguard-rules.pro` file enforces that no `android.util.Log` functions are shipped to production binaries.

---

## Final Pre-Release Checklist
- [ ] Ensure `app/keystore/release.jks` is safely backed up.
- [ ] Confirm `libs.versions.toml` dependencies are up-to-date and stable.
- [ ] Increment `versionCode` in `app/build.gradle.kts`.
- [ ] Run the complete test suite: `./gradlew test`
- [ ] Build release APK: `./gradlew assembleRelease`
- [ ] Test the release APK on a physical device to ensure ProGuard hasn't broken FFmpegKit or NewPipe Extractor JNI bindings.
