# Drive Alert

**Safer roads for a better India.** Drive Alert is an Android app that warns drivers, by voice and with a heads-up notification, before they reach verified speed cameras, red-light cameras, ANPR/e-challan cameras and other traffic-enforcement cameras. It runs alongside Google Maps or any other navigation app.

> **Safety statement.** Drive Alert supports speed-limit compliance and safer driving. It is not a tool for avoiding penalties or evading enforcement. Camera data may be incomplete, outdated or wrong. Not every camera is included, and a missing warning never means that speeding is safe or permitted. Always obey posted limits and road signs.

## How it works

1. The user opens Drive Alert, grants notification and **precise** location permission, and does a short GPS accuracy check.
2. They press **Start Camera Alerts → Start Drive**. A user-started *location foreground service* shows the persistent "Camera alerts active" notification with a **Stop** button.
3. The user switches to Google Maps and drives as usual. Drive Alert **never reads Google Maps' route or screen**. It decides relevance from its own GPS position, direction of travel, speed and each camera's monitored direction.
4. Before a relevant camera (about 1 km / 500 m / 200 m, moved earlier at speed) it speaks a warning and shows a heads-up such as **"Speed camera ahead · 500 m • Speed limit 60 km/h · Current speed 48 km/h — Drive safely"**.
5. The drive ends from the notification, from the app, or after a "Has your drive ended?" prompt and a 30-minute no-movement timeout. The service never restarts by itself.

## Features

- Camera relevance engine: ahead vs behind, direction matching, opposite carriageways, crossing roads, parallel service lanes and flyovers, GPS-accuracy and confidence scoring, and once-per-band duplicate suppression.
- Battery-first adaptive tracking: no tracking when alerts are off; passive or balanced-power updates when no camera is near; balanced updates every 12 s within 5 km; high-accuracy updates every 2–4 s only near a relevant camera.
- Offline: a Room database of city/state camera packages with a grid spatial index, plus incremental sync over HTTPS via WorkManager (Wi-Fi by default).
- Text-to-speech on the navigation audio stream (ducks music), an optional chime and vibration, and a silent visual-only mode. English, with Hindi for the core driving flow.
- Setup assistant with OnePlus / OxygenOS battery guidance plus Xiaomi, vivo, Samsung and generic paths.
- Moderated community reports: rate limited, de-duplicated, and never trusted automatically.
- Privacy: on-device matching, no trip upload, no ads, local-only history without coordinates, no cloud backup of location data, and no coordinates in production logs.

## Tech stack

Kotlin 2.2 · Jetpack Compose + Material 3 · Hilt · Room · DataStore · WorkManager · Fused Location Provider · Maps Compose · Retrofit/OkHttp + kotlinx.serialization · AGP 8.13 / Gradle 8.14 · compileSdk/targetSdk 36, minSdk 26. Versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Quick start

```bash
git clone https://github.com/Ansh1040/map.git
cd map
./gradlew testDebugUnitTest assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Full setup, configuration and troubleshooting: [docs/12-setup-and-build.md](docs/12-setup-and-build.md).

## Required before release

| Item | Where |
|---|---|
| **TODO (required before release):** licensed, legally reusable camera data source plus the backend implementing [docs/server-api-contract.md](docs/server-api-contract.md) | `CAMERA_API_BASE_URL` |
| **TODO:** Google Maps SDK key restricted to the app's package and signing certificate | `MAPS_API_KEY` |
| **TODO:** hosted privacy policy URL, data-controller and grievance contact | `PRIVACY_POLICY_URL`, `privacy_p_contact` string |
| **TODO:** release signing configuration | `app/build.gradle.kts` |
| **TODO:** native-speaker review of Hindi strings | `app/src/main/res/values-hi/strings.xml` |
| **TODO:** licence for this repository | `LICENSE` |

Put these keys in `local.properties` (git-ignored) or pass them as `-P` Gradle properties. The app builds and runs without them: the map shows a placeholder, sync is disabled, and alerts work with the debug-only TEST-ONLY synthetic cameras.

## Documentation

| # | Document |
|---|---|
| 01 | [Product requirements](docs/01-product-requirements.md) |
| 02 | [Technical architecture](docs/02-technical-architecture.md) |
| 03 | [User flows](docs/03-user-flows.md) |
| 04 | [Database schema](docs/04-database-schema.md) |
| 05 | [Camera-relevance and adaptive-location algorithms](docs/05-algorithms.md) |
| 06 | [Permissions and OnePlus battery setup](docs/06-permissions-and-battery-setup.md) |
| 07 | [UI specifications](docs/07-ui-specifications.md) |
| 08 | [Project structure](docs/08-project-structure.md) |
| 10 | [Testing strategy](docs/10-testing-strategy.md) |
| 11 | [TEST-ONLY mock dataset](docs/11-mock-dataset.md) |
| 12 | [Setup and build](docs/12-setup-and-build.md) |
| 13 | [Privacy and Play Store compliance checklist](docs/13-privacy-and-play-compliance.md) |
| 14 | [OnePlus 13 real-device test checklist](docs/14-oneplus13-device-test-checklist.md) |
| 15 | [Battery-profiling report template](docs/15-battery-profiling-report-template.md) |
| — | [Server API contract](docs/server-api-contract.md) |

Deliverable 9 (the Kotlin/Compose implementation) is the `app/` module itself.

## Licence

**TODO:** choose a licence before publishing.
