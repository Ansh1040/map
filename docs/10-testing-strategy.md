# 10 · Testing Strategy

Drive Alert keeps all safety-relevant logic in the Android-free `domain` package, so most behaviour (what the driver hears, and how much GPS power is used) is verified with fast JVM tests. Android integration points are covered with Robolectric and a small set of on-device tests, and the rest is covered by the manual OnePlus 13 checklist.

```text
            ┌──────────────────────────────┐
            │ Manual drive tests (doc 14)  │  Google Maps in front, locked screen, OEM battery modes
            ├──────────────────────────────┤
            │ Instrumented (androidTest) 3 │  real SQLite, real foreground service + notification
            ├──────────────────────────────┤
            │ Robolectric (test)        29 │  Room, reports, notifications, service lifecycle     
            ├──────────────────────────────┤
            │ Pure JVM unit (test)     118 │  geometry, relevance, alerts, power modes, OSM, drive sim
            └──────────────────────────────┘
```

All test cameras are synthetic (`DataSource.TEST_ONLY_SYNTHETIC`, built from offsets around an arbitrary origin in `testutil/Fixtures.kt`). No test contains real camera locations.

## Unit tests (`app/src/test`)

| Suite | Cases | What it proves |
|---|---|---|
| `domain/geo/GeoMathTest` | 9 | Haversine distance, bearings, angle wrap-around, destination round trip, along/cross-track split, grid cells and bounding box always enclose the radius |
| `domain/relevance/BearingMatcherTest` | 8 | Same direction / **opposite carriageway** / crossing road, widened tolerance for coarse headings, bidirectional cameras, **ahead vs behind vs beside** |
| `domain/relevance/CameraRelevanceEngineTest` | 17 | Ahead camera alerts; behind, opposite-carriageway and crossing cameras rejected; **parallel service lane** and **flyover** handling; **inaccurate GPS**; no heading; unverified opt-in; expired/inactive/out-of-range skipped; map-matching and elevation signals; sorting |
| `domain/alerts/AlertThresholdPolicyTest` | 7 | Speed- and accuracy-adjusted trigger distances, ≥ 8 s final warning, accuracy caps, rounding of spoken distances, threshold lists |
| `domain/alerts/SessionCameraTrackerTest` | 9 | Each band fires once, alerts are never late, one alert when starting inside a band, **GPS-jitter duplicate suppression**, **passing a camera**, silence after passing, reset for a new session, re-arm after the gap, inaccurate fixes can't "pass", turning away abandons without suppressing a later approach |
| `domain/location/TrackingModePolicyTest` | 11 | **Adaptive modes** 2→3→4, non-candidate cameras never trigger high accuracy, speed-dependent close distance, hysteresis, stationary downgrade, exact priorities/intervals per plan, passive fallback on/off |
| `domain/location/AdaptiveLocationControllerTest` | 5 | No requests while off, mode progression, requests re-issued only on change, passive fallback, **stop removes everything and nothing re-registers** |
| `domain/location/StationaryAndDeadReckoningTest` | 6 | Parked detection, **traffic jams never time out**, movement restarts the timer, no unbounded extrapolation, prompt/auto-stop thresholds, dead-reckoning geometry |
| `domain/cameras/NearbyCameraIndexTest` | 4 | Radius filtering, **database queried only after moving kilometres**, cache coverage guarantee, invalidation |
| `domain/cameras/NearbyCamerasTest` | 7 | "Cameras near you" ranking: nearest first, 25 km cut-off (default) and explicit `maxDistanceM`, result limit (10 by default), unverified only on opt-in and rejected never, expired and inactive skipped, bearing from the user to the camera, 8-point compass sectors |
| `domain/session/DrivingSessionManagerTest` | 9 | Full simulated drive at 72 km/h past synthetic cameras: only the relevant camera warns, once per band; **high-accuracy GPS only near the camera** and released at it; stop cleans up; fresh state per session; long-stationary prompt and auto-stop; traffic jam; **tunnel GPS loss** bridged by dead reckoning; cached location at start |
| `domain/reporting/ReportModerationPolicyTest` | 8 | Rate limiting, duplicate detection, validation, temporary-report expiry, crowdsourced data never auto-trusted, disputes/abuse, confidence decay |
| `data/testdata/SyntheticCameraDatasetTest` | 3 | The debug dataset is labelled TEST-ONLY, positioned relative to the anchor, and each scenario TEST-001…010 produces its documented outcome |
| `database/CameraDatabaseTest` (Robolectric) | 7 | Spatial `IN (cells)` query, field round trip, incremental delta + full replace, inactive/expired handling, search, history without coordinates, report audit trail + expiry |
| `data/osm/OsmCameraParserTest` | 15 | OSM tag mapping: `direction` = way the camera faces (reversed to travel), negative degrees, both-ways values, mph, ALPR, enforcement relations (reliable geometry only; 20 m minimum; contradicting from/to → unknown; relation overrides a contradicting tag), region ownership; the bundled India snapshot (≥ 500 cameras, all inside India) and real-data cross-checks against nodes 11959592200, 12961358380, 12961358381 and junction relations 17863216, 17931498, 13405202 |
| `reporting/ReportRepositoryTest` (Robolectric) | 10 | No publishing without consent; reports placed at the camera (not the reporter) unless they are position reports; wrong-position reports far from the camera, imprecise or mock positions and TEST-ONLY cameras are kept on the phone; legacy unconsented reports are never published; a lost response is not re-posted; unreadable 2xx ids count as published; 4xx is not retried |
| `notifications/NotificationsTest` (Robolectric) | 8 | High-importance silent alert channel, exact notification text ("Speed camera ahead", "500 m • Speed limit 60 km/h", "Current speed 48 km/h — Drive safely"), **no full-screen intent and no silent group** (which would block the heads-up), slow-down wording, unverified labelling, posting/cancelling, voice phrases, ongoing session notification with Stop |
| `location/service/CameraAlertServiceTest` (Robolectric + Hilt) | 4 | **Foreground service**: starts in the foreground with "Camera alerts active", `START_NOT_STICKY`, Stop action ends the session and removes location callbacks, null-intent recreation never resumes tracking, refuses without precise location, destroy stops the session |

## Instrumented tests (`app/src/androidTest`)

| Suite | What it proves on a real device |
|---|---|
| `CameraDatabaseInstrumentedTest` | 5 000-camera spatial lookup on device SQLite in < 200 ms |
| `CameraAlertServiceInstrumentedTest` | Real foreground service shows the persistent notification, Stop removes it, and it does not come back |

## Running

```bash
./gradlew testDebugUnitTest            # all JVM + Robolectric tests (report: app/build/reports/tests/testDebugUnitTest/index.html)
./gradlew connectedDebugAndroidTest    # device/emulator connected (OnePlus 13: enable USB debugging)
```

Robolectric downloads its Android runtime jar into `~/.m2` on the first run (network needed once), and needs a few hundred MB of free temp space. On a nearly full system drive, point `TEMP`/`TMP` and `-Djava.io.tmpdir` at another drive.

## What is not automated

The scenarios that depend on OEM power management, other apps in the foreground or real roads are in [14-oneplus13-device-test-checklist.md](14-oneplus13-device-test-checklist.md): Google Maps visible during alerts, locked screen, Power Saving Mode, mobile data off, tunnels, opposite carriageways, parallel roads, flyovers, Bluetooth car audio, Hindi locale and TalkBack. Battery measurement follows [15-battery-profiling-report-template.md](15-battery-profiling-report-template.md).
