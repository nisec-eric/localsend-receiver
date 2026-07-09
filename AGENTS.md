# AGENTS.md

## Project Overview

LocalSend Receiver — a minimal Android app that receives files from other LocalSend devices.
Targets Android 4.2.2 (API 17) car head units, Android TV, and modern phones.

## Build & Run

```bash
# Build release APK (signed, minified)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk

# Build debug APK
./gradlew assembleDebug

# Clean
./gradlew clean
```

Requirements: JDK 17, Gradle 8.5, Android SDK 34.

## Tech Stack

- Kotlin 1.9.22, minSdk 17, targetSdk 28, compileSdk 34
- NanoHTTPD 2.3.1 (HTTP server)
- Gson 2.8.9 (JSON)
- Single DEX file (no coreLibraryDesugaring, no multidex splitting)

## Architecture

```
MainActivity (UI: status + file manager)
  ↕ static bridge
LocalSendService (foreground service)
  ├── MulticastDiscoveryService (UDP 224.0.0.167:53317)
  ├── LocalSendHttpServer (NanoHTTPD, port 53317)
  │     └── 5 endpoints: info, register, prepare-upload, upload, cancel
  ├── HttpDiscoveryResponder (HTTP POST register back to sender)
  ├── FileReceiver (stream save to Download/LocalSend/)
  └── SessionManager (transfer session tracking)
```

## Key Constraints

- **No coreLibraryDesugaring** — it replaces java.util.* with j$.util.* in a separate DEX that Dalvik API 17 may fail to load. We manually avoid API 19+ classes.
- **No StandardCharsets** — use `Charset.forName("UTF-8")` instead (StandardCharsets is API 19+).
- **No java.util.Objects** — Gson 2.8.9 avoids it; 2.10+ uses it.
- **catch(Throwable) in network threads** — `NoClassDefFoundError` / `VerifyError` extend `Error`, not `Exception`.
- **NanoHTTPD safeClose()** — pre-built JAR compiled with Java 6, `instanceof Closeable` check works correctly on API 17 (returns false for Socket, falls through to instanceof Socket).
- Files saved to `Environment.DIRECTORY_DOWNLOADS/LocalSend/`.

## Signing

Keystore: `localsend-release.jks` (password: `localsend123`, alias: `localsend`).
Not committed to git (in .gitignore). CI uses debug signing or injects keystore via secrets.

## File Layout

```
app/src/main/java/com/localsend/receiver/
├── App.kt                    # MultiDexApplication
├── ui/MainActivity.kt        # Activity: status panel + file manager
├── service/LocalSendService.kt
├── network/
│   ├── LocalSendHttpServer.kt
│   ├── MulticastDiscoveryService.kt
│   ├── HttpDiscoveryResponder.kt
│   ├── FileReceiver.kt
│   ├── SessionManager.kt
│   └── dto/ProtocolDto.kt
└── util/
    ├── DeviceConfig.kt
    └── NetworkUtils.kt
```

## CI

GitHub Actions workflow at `.github/workflows/build.yml`.
Triggers on push to main and tags `v*`.
Uploads APK as artifact and creates GitHub Release on tags.
