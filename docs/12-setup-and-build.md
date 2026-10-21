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

## 3. Configuration — nothing is required

Every setting has a free, open default, so a fresh clone builds and runs with no accounts or keys. To override one, set it as a `-P` Gradle property, in `local.properties` (git-ignored) or as an environment variable:

| Key | Default | Purpose |
|---|---|---|
| `OSM_SNAPSHOT_URL` | `https://raw.githubusercontent.com/Ansh1040/map/main/app/src/main/assets/osm/india_cameras.json` | Weekly OpenStreetMap camera snapshot the app downloads (no location sent) |
| `MAP_STYLE_URL` | `https://tiles.openfreemap.org/styles/liberty` | MapLibre style (OpenFreeMap, free, no key) |
| `PRIVACY_POLICY_URL` | `https://github.com/Ansh1040/map/blob/main/PRIVACY.md` | Linked from the in-app policy and for Play Console |
| `CAMERA_API_BASE_URL` | *(empty)* | **Optional** licensed server ([server-api-contract.md](server-api-contract.md)). Empty = OpenStreetMap mode |

The camera snapshot is regenerated every Monday by `.github/workflows/update-osm-snapshot.yml` (runs `tools/fetch_osm_cameras.py` against Overpass and commits the file if it changed). To refresh it by hand: `python tools/fetch_osm_cameras.py`.

**Safety guard.** The builder refuses to overwrite the snapshot (exit code 2, nothing committed) when Overpass returned an incomplete result, when a state that had cameras disappears, or when a state with 5 or more cameras drops to 0 or loses more than 5 cameras and more than half. If such a change is real (e.g. a large OpenStreetMap clean-up), override it locally with `python tools/fetch_osm_cameras.py --force`, or run the workflow manually (GitHub → Actions → Update OSM camera snapshot → Run workflow) with the **force** input ticked.

## 4. Build and test commands

Use `./gradlew` (macOS/Linux/Git Bash) or `gradlew.bat` (Windows cmd/PowerShell).

| Task | Command |
|---|---|
| Debug APK (includes test tools + simulated drive) | `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk` |
| Install debug on a USB-connected device | `./gradlew installDebug` |
| JVM unit + Robolectric tests | `./gradlew testDebugUnitTest` |
| Single test class | `./gradlew testDebugUnitTest --tests "com.drivealert.app.data.osm.OsmCameraParserTest"` |
| Instrumented tests (device/emulator connected) | `./gradlew connectedDebugAndroidTest` |
| Release APK (minified, signed) | `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` |
| Release bundle for Play | `./gradlew bundleRelease` |

## 5. Signing

`app/build.gradle.kts` signs `release` with the key described in a git-ignored **`keystore.properties`** at the repo root:

```properties
storeFile=keystore/drivealert-upload.jks
storePassword=…
keyAlias=drivealert-upload
keyPassword=…
```

Without that file the release build is signed with the **debug key** (it installs fine, but cannot be published to Play). To create your own upload key:

```bash
mkdir -p keystore   # PowerShell: New-Item -ItemType Directory -Force keystore
keytool -genkeypair -keystore keystore/drivealert-upload.jks -storetype PKCS12 -alias drivealert-upload -keyalg RSA -keysize 4096 -validity 10000
```

**Back up `keystore/` and `keystore.properties` somewhere safe** (both are git-ignored). For Google Play, enrol in Play App Signing and upload with this key; a lost upload key can be reset through Play support.

## 6. Testing without driving (debug build)

See [11-mock-dataset.md](11-mock-dataset.md). On the phone:

1. Install `app-debug.apk`, finish onboarding.
2. Developer options → **Select mock location app → Drive Alert (debug)** (Settings → Developer in the app has a shortcut).
3. Drive Alert → Settings → Developer → **Simulate a test drive (mock GPS)**. Alerts start automatically and a virtual car drives 7 km north at 72 km/h past the TEST-ONLY scenario; expect warnings for **TEST-001** and **TEST-006** only. Open Google Maps or lock the screen while it runs. Tap again to stop; mock mode is switched off when the drive ends.

Manual alternative: *Load TEST-ONLY cameras ahead of me*, then drive a straight road. Release builds contain neither the dataset nor the simulator.

Useful debug commands (debug package id is `com.drivealert.app.debug`):

```bash
adb logcat -s FusedLocationSource CameraAlertService   # plan changes (debug-only log), service events
adb shell dumpsys activity services com.drivealert.app.debug
adb shell pm clear com.drivealert.app.debug              # reset onboarding, database, settings
```

## 7. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `Unresolved reference: R.string.…` | A key used in code is missing from `res/values/strings.xml`. |
| KSP / Hilt "cannot find symbol … Hilt_…" | Run `./gradlew clean` then rebuild; ensure the JDK in *Settings → Build Tools → Gradle* is 17+. |
| "Unsupported class file major version" | Gradle is running on an old JDK; point `JAVA_HOME` / Studio's Gradle JDK to 17 or 21. |
| Room "Schema export directory" warning | Expected on first build; commit the generated `app/schemas/*.json`. |
| Map card says "Map needs an internet connection" | Offline or OpenFreeMap unreachable; alerts still work offline. |
| "No camera data" chip | All state packages switched off (Settings → Camera data packages), or the first-launch import has not finished yet. |
| "Simulate a test drive" says to choose a mock location app | Developer options → Select mock location app → Drive Alert (debug). |
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
