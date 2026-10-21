# Drive Alert

**Safer roads for a better India.** Drive Alert is a free, open-source Android app that warns drivers by voice and with a heads-up notification before they reach speed cameras, red-light cameras, ANPR/e-challan cameras and other traffic-enforcement cameras. It runs alongside Google Maps or any other navigation app.

> **Safety statement.** Drive Alert supports speed-limit compliance and safer driving. It is not a tool for avoiding penalties or evading enforcement. Camera data comes from OpenStreetMap volunteers and may be incomplete, outdated or wrong. Not every camera is included, and a missing warning never means speeding is safe or permitted. Always obey posted limits and road signs.

**No accounts, keys or servers needed.** Everything uses free, open services:

| Need | Open solution |
|---|---|
| Camera data | [OpenStreetMap](https://www.openstreetmap.org/copyright) (ODbL). 581 mapped cameras across 36 states/UTs are bundled for offline use, refreshed weekly by a GitHub Action. |
| Map | [MapLibre](https://maplibre.org) + [OpenFreeMap](https://openfreemap.org) tiles (no API key) |
| Report moderation | Optional public [OpenStreetMap notes](https://wiki.openstreetmap.org/wiki/Notes) reviewed by OSM volunteers (opt-in per report) |
| Privacy policy | [PRIVACY.md](PRIVACY.md) in this repository |
| Licence | Code: [Apache-2.0](LICENSE) · Data: ODbL ([NOTICE](NOTICE)) |

## How it works

1. Open Drive Alert, allow notifications and **precise** location, and do a short GPS accuracy check.
2. Press **Start Camera Alerts → Start Drive**. A user-started *location foreground service* shows "Camera alerts active" with a **Stop** button.
3. Switch to Google Maps and drive as usual. Drive Alert **never reads Google Maps' route or screen**. It uses its own GPS position, direction of travel, speed, and each camera's monitored direction.
4. Before a relevant camera (about 1 km / 500 m / 200 m, moved earlier at speed) it speaks a warning and shows a heads-up such as **"Speed camera ahead · 500 m • Speed limit 60 km/h · Current speed 48 km/h — Drive safely"**.
5. Stop the drive from the notification, from the app, or after a "Has your drive ended?" prompt and a 30-minute no-movement timeout. The service never restarts by itself.

**See the cameras around you.** The Home map shows every camera in your installed packages (unverified reports only if you turn them on). Nothing is hidden when you zoom out: nearby cameras merge into numbered clusters, while the cameras in your **Cameras near you** list always keep their own marker. Where OpenStreetMap records a camera's direction, an arrow shows the direction of travel the camera watches, double-headed when it watches both directions. Cameras without direction data have no arrow; today that is most of them, as only about 1 in 4 has one. The map opens framed on you and your 3 nearest cameras. The **Cameras near you** list, below the Start button, shows the nearest cameras within 25 km (5 at first, up to 10 with *Show all*), each with its distance and direction from you (for example "2.3 km north-east of you") and its speed limit when known. The list needs precise location, and it is hidden during a drive, when the "Camera alerts active" card shows the next camera instead. The map pin on a row moves the map to that camera, and the camera's details page shows the same "from your position" line.

## Coverage today

OpenStreetMap coverage in India is uneven: Kerala 284, Karnataka 96, Maharashtra 87, Delhi 50, Uttar Pradesh 19, Rajasthan 15, a few elsewhere, and none yet in many states. Coverage improves as volunteers map more cameras. Drive Alert does not claim nationwide accuracy.

## Try it on your phone (OnePlus 13 or any Android 8+)

1. Build or download the APK (see below) and install it. Use **`app-debug.apk` for testing**, because it includes the test tools.
2. Go through onboarding, then **Settings → Setup assistant**. On OnePlus, also lock Drive Alert in Recents.
3. **No driving needed:** in Developer options choose **Select mock location app → Drive Alert (debug)**. Then use **Settings → Developer → Simulate a test drive**. A virtual car drives 7 km north at 72 km/h past the TEST-ONLY cameras. Expect warnings for TEST-001 and TEST-006 only. Switch to Google Maps, or lock the screen, while it runs.
4. On real roads in Kerala, Bengaluru, Pune/Mumbai or Delhi, the real OpenStreetMap cameras alert as you drive.

The full checklist is [docs/14-oneplus13-device-test-checklist.md](docs/14-oneplus13-device-test-checklist.md).

## Build

```bash
git clone https://github.com/Ansh1040/map.git && cd map
./gradlew testDebugUnitTest assembleDebug assembleRelease
# app/build/outputs/apk/debug/app-debug.apk       (test tools included)
# app/build/outputs/apk/release/app-release.apk   (signed if keystore.properties exists, else debug-signed)
```

Requirements, signing and troubleshooting: [docs/12-setup-and-build.md](docs/12-setup-and-build.md).

## Features

- Relevance engine: ahead vs behind, direction matching, opposite carriageways, crossing roads, parallel service lanes and flyovers, GPS-accuracy and confidence scoring, and once-per-band duplicate suppression.
- Battery-first adaptive tracking: nothing when alerts are off; passive or balanced updates far from cameras; balanced every 12 s within 5 km; high accuracy every 2–4 s only near a relevant camera.
- Offline-first Room database with a grid spatial index, organised as state packages.
- Voice (TTS on the navigation audio stream), chime, vibration, and a silent visual-only mode. English and Hindi.
- OnePlus/OxygenOS setup assistant, plus Xiaomi, vivo, Samsung and generic guidance.
- Privacy: on-device matching, no trip upload, no ads or analytics, local-only history without coordinates, no cloud backup.

Tech: Kotlin 2.2 · Compose Material 3 · Hilt · Room · DataStore · WorkManager · Fused Location Provider · MapLibre · OkHttp/Retrofit · AGP 8.13 · target SDK 36 (Android 16), min SDK 26.

## Documentation

| # | Document |
|---|---|
| 01 | [Product requirements](docs/01-product-requirements.md) |
| 02 | [Technical architecture](docs/02-technical-architecture.md) |
| 03 | [User flows](docs/03-user-flows.md) |
| 04 | [Database schema](docs/04-database-schema.md) |
| 05 | [Algorithms (relevance, alerts, adaptive location, OSM import)](docs/05-algorithms.md) |
| 06 | [Permissions and OnePlus battery setup](docs/06-permissions-and-battery-setup.md) |
| 07 | [UI specifications](docs/07-ui-specifications.md) |
| 08 | [Project structure](docs/08-project-structure.md) |
| 10 | [Testing strategy](docs/10-testing-strategy.md) |
| 11 | [TEST-ONLY mock dataset and simulated drive](docs/11-mock-dataset.md) |
| 12 | [Setup and build](docs/12-setup-and-build.md) |
| 13 | [Privacy and Play Store compliance](docs/13-privacy-and-play-compliance.md) |
| 14 | [OnePlus 13 device test checklist](docs/14-oneplus13-device-test-checklist.md) |
| 15 | [Battery-profiling report template](docs/15-battery-profiling-report-template.md) |
| — | [Optional server API contract](docs/server-api-contract.md) |

## Still needs a person

- **Back up the release keystore** (`keystore/` and `keystore.properties`, both git-ignored). Losing it means you can't publish updates under the same key (Google Play can reset an upload key).
- **Native-speaker review** of the Hindi strings (`app/src/main/res/values-hi/strings.xml`). Corrections are welcome as issues.
- **Real-road testing** on the phone (tunnels, flyovers, car Bluetooth), and the battery report ([docs/15](docs/15-battery-profiling-report-template.md)).

## Licence

Code: [Apache License 2.0](LICENSE). Camera data: © OpenStreetMap contributors, [ODbL 1.0](https://www.openstreetmap.org/copyright). See [NOTICE](NOTICE).
