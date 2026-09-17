# NewPipe Extractor Maintenance & Update Guide

## 1. Why YouTube Extraction Breaks
YouTube regularly alters its web player client code (`base.js` and player JavaScript configurations):
- **Signature Deciphering Changes**: The JavaScript algorithm that transforms the `s` query parameter into a valid playback token is refactored by YouTube.
- **Throttling Parameters (`n` parameter)**: YouTube challenges video stream downloads by calculating an `n` token function. If not properly calculated, playback and download speeds drop to ~40 KB/s or fail with HTTP 403.
- **Player Response Layouts**: Changes to YouTube's internal JSON or Protobuf payload schemas.

When these occur, `YouTubeAdapter.healthCheck()` transitions to `AdapterStatus.BROKEN`, and extraction requests throw:
```
AppError.ExtractionFailed(platform = PlatformId.YOUTUBE, cause = ParsingException)
```

---

## 2. Diagnosing Broken Extraction
1. **Check Adapter Status**:
   ```kotlin
   val status = youTubeAdapter.healthCheck()
   if (status == AdapterStatus.BROKEN) {
       // Extraction is broken by upstream YouTube player changes
   }
   ```
2. **Review Stack Trace**: Look for `org.schabi.newpipe.extractor.exceptions.ParsingException: Could not decipher signature` or `Could not parse player response`.

---

## 3. Step-by-Step Update Procedure

### Step 1: Check Upstream NewPipe Releases
Check the official repository for hotfixes:
- **Releases**: [TeamNewPipe/NewPipeExtractor Releases](https://github.com/TeamNewPipe/NewPipeExtractor/releases)
- **Active Issues/PRs**: [TeamNewPipe/NewPipeExtractor Pull Requests](https://github.com/TeamNewPipe/NewPipeExtractor/pulls)

### Step 2: Bump Dependency Version
In `gradle/libs.versions.toml`:
```toml
[versions]
# Update to the latest stable or release-candidate version
newpipeExtractor = "v0.24.5" # Replace with latest tag
```

### Step 3: Fast-Track Hotfix via JitPack (Emergency Fallback)
If an official tag is pending release but a fix exists on the `dev` branch or an open PR commit:
```toml
[versions]
# JitPack can resolve direct commit SHAs
newpipeExtractor = "a1b2c3d4e5"
```

### Step 4: Verify Resolution
Run automated unit tests and probe extraction:
```bash
./gradlew :platform:youtube:test
```

---

## 4. Architectural Safeguards
- **Zero Credentials Policy**: The extractor never uses user accounts or cookies.
- **No Manual Deciphering**: Never implement custom regex deciphering in local code; rely strictly on NewPipe Extractor's maintained cipher engines.
- **Graceful UI Feedback**: When extraction fails, `AppError.ExtractionFailed` informs the user that YouTube has updated its layout and an update is pending, rather than crashing or hanging.
