# 12 · Setup and Build

## 1. Prerequisites

| Tool | Version |
|---|---|
| JDK | **17 or newer** (the project is built with **JDK 21**; bytecode targets 17) |
| Android SDK | **Platform 36** (`compileSdk`/`targetSdk` 36) and **Build-Tools 35.0.0** |
| Gradle | 8.14.3 via the wrapper — do not install separately |
| Android Gradle Plugin | 8.13.2 · Kotlin 2.2.21 · KSP 2.2.21-2.0.5 |
| Android Studio | A current stable release that supports AGP 8.13 (check the AGP ↔ Studio compatibility table) |
| Device | Android 8.0+ (`minSdk 26`); reference device **OnePlus 13** |

## 2. Clone and open

```bash
git clone https://github.com/Ansh1040/map.git drive-alert
cd drive-alert
# Android Studio: File → Open → select the folder; let Gradle sync.
```

Android Studio writes `local.properties` with `sdk.dir`. On the command line create it yourself (Windows paths need escaped backslashes, e.g. `sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`).

## 3. Required configuration — `local.properties`

`app/build.gradle.kts` reads each key from, in order: `-P` Gradle property → `local.properties` → environment variable. `local.properties` is git-ignored; **never commit keys**.

```properties
sdk.dir=/path/to/Android/Sdk

# TODO (required before release): Google Maps SDK for Android key, restricted to
# package com.drivealert.app (and com.drivealert.app.debug for debug builds) + signing SHA-1.
MAPS_API_KEY=

# TODO (required before release): HTTPS base URL of the LICENSED camera-data service.
# Must start with https:// — otherwise sync and report upload stay disabled.
CAMERA_API_BASE_URL=

# TODO (required before release): public URL of the hosted privacy policy (Play Console requires it).
PRIVACY_POLICY_URL=
```

| Key | If missing | Exposed as |
|---|---|---|
| `MAPS_API_KEY` | Home map shows a "Map preview unavailable" placeholder; **alerts still work** | Manifest `com.google.android.geo.API_KEY`, `BuildConfig.MAPS_CONFIGURED` |
| `CAMERA_API_BASE_URL` | `ApiConfig.isConfigured = false`: no catalog, no sync, reports stay queued locally | `BuildConfig.CAMERA_API_BASE_URL` |
| `PRIVACY_POLICY_URL` | In-app policy only; no "view online" link | `BuildConfig.PRIVACY_POLICY_URL` |

> The build currently **does not fail** when these are empty. Before release, verify all three are set in the CI release job (consider failing `assembleRelease` when blank).

## 4. Build and test commands

Use `./gradlew` (macOS/Linux/Git Bash) or `gradlew.bat` (Windows cmd/PowerShell).

| Task | Command |
|---|---|
| Debug APK | `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| Install debug on a device | `./gradlew installDebug` |
| JVM unit + Robolectric tests | `./gradlew testDebugUnitTest` |
| Single test class | `./gradlew testDebugUnitTest --tests "com.drivealert.app.domain.relevance.CameraRelevanceEngineTest"` |
| Instrumented tests (device/emulator connected) | `./gradlew connectedDebugAndroidTest` |
| Lint | `./gradlew lintDebug` |
| Release APK (minified) | `./gradlew assembleRelease` |
| Release bundle for Play | `./gradlew bundleRelease` |

## 5. Signing — TODO (required before release)

`release` has no `signingConfig` (`TODO(release)` in `app/build.gradle.kts`). Before publishing:

1. Create an upload keystore (outside the repo) and enrol in **Play App Signing**.
2. Add a `signingConfigs { create("release") { … } }` block reading from a git-ignored `keystore.properties` or CI secrets (`*.jks`, `*.keystore`, `keystore.properties` are already in `.gitignore`).
3. Add the upload and app-signing SHA-1 fingerprints to the Maps API key restriction.

## 6. Loading test cameras in a debug build

See [11-mock-dataset.md](11-mock-dataset.md). Summary: install the **debug** variant → stand at the start of a straight test road facing the direction of travel → *Settings → Developer (debug build only) → Load TEST-ONLY cameras ahead of me* (calls `TestDataTools.loadSyntheticCamerasAround`) → start alerts. Remove with *Remove TEST-ONLY cameras*. The release variant contains neither the asset nor the loader.

Useful debug commands (debug package id is `com.drivealert.app.debug`):

```bash
adb logcat -s FusedLocationSource CameraAlertService   # plan changes (debug-only log), service events
adb shell dumpsys location | grep -A3 drivealert         # active location requests and priorities
adb shell dumpsys activity services com.drivealert.app.debug
adb shell pm clear com.drivealert.app.debug              # reset onboarding, database, settings
```

## 7. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `Unresolved reference: R.string.…` | A key used in code is missing from `res/values/strings.xml`. |
| Unresolved screen composables in `DriveAlertNavHost` | History/Settings/Setup/Privacy/Packages screens not yet merged. |
| KSP / Hilt "cannot find symbol … Hilt_…" | Run `./gradlew clean` then rebuild; ensure the JDK in *Settings → Build Tools → Gradle* is 17+. |
| "Unsupported class file major version" | Gradle is running on an old JDK; point `JAVA_HOME` / Studio's Gradle JDK to 17 or 21. |
| Room "Schema export directory" warning | Expected on first build; commit the generated `app/schemas/*.json`. |
| Map card shows "Map preview unavailable" | `MAPS_API_KEY` missing, or key restricted to a different package/SHA-1 (debug uses `.debug`). |
| "No camera data" chip; sync does nothing | `CAMERA_API_BASE_URL` missing or not `https://`; or no package installed. Load test cameras in debug. |
| *Start Drive* disabled | Precise location not granted or location services off (banner explains). It enables after 20 s even with weak GPS ("Start drive anyway"). |
| No heads-up, but voice works | Notifications denied or the "Camera alerts" channel turned off → Setup Assistant. |
| Alerts stop after a while on OnePlus | Battery management — see [06-permissions-and-battery-setup.md](06-permissions-and-battery-setup.md) §4. |
| Robolectric tests fail on SDK download | Robolectric is pinned to SDK 34 (`app/src/test/resources/robolectric.properties`); first run needs network access to fetch the android-all jar. |

## Low disk space on the system drive

Gradle, Robolectric and the JVM write caches and temp files to the user profile. If the system drive is nearly full, builds fail with "There is not enough space on the disk" or JVM `malloc` errors. Either free space, or redirect the heavy I/O for one build:

```bash
export GRADLE_USER_HOME=D:/gradle-home          # new caches on another drive
export GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches # reuse already-downloaded dependencies (read-only)
export TEMP=D:/tmp TMP=D:/tmp JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=D:/tmp
./gradlew --no-parallel -Pkotlin.compiler.execution.strategy=in-process testDebugUnitTest assembleDebug
```
