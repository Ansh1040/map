# 11 · TEST-ONLY Synthetic Camera Dataset

> **This dataset contains no real camera locations.** It is a geometric test fixture. It must never be shipped in a release build, published, or used for navigation.

| Item | Value |
|---|---|
| File | `app/src/debug/assets/test_only_mock_cameras.json` |
| Parser / placer | `data/testdata/SyntheticCameraDataset.kt` |
| Loader (debug only) | `app/src/debug/java/com/drivealert/app/debugtools/DebugTestDataTools.kt` |
| Release binding | `NoOpTestDataTools` (`isAvailable = false`, loads nothing) |
| Package id | `test-only-synthetic` (`data_packages.is_test_data = 1`) |
| Unit test | `app/src/test/java/com/drivealert/app/data/testdata/SyntheticCameraDatasetTest.kt` (the `test` source set adds `src/debug/assets` as a resource dir) |

## 1. Format: relative offsets, not coordinates

Each entry is placed relative to an **anchor** — the tester's position and heading at load time:

| Field | Meaning |
|---|---|
| `ahead_m` | Metres along the anchor heading (negative = behind) |
| `right_m` | Metres to the right of the heading (negative = left). India drives on the left, so the opposite carriageway is to the right. |
| `bearing_offset_deg` | Monitored direction relative to the anchor heading (0 = same direction as the tester, 180 = opposite) |
| `directionality`, `type`, `speed_limit_kmh`, `verification_status`, `road_context` | As in `Camera` |

`SyntheticCameraDataset.materialize(file, anchorLat, anchorLon, anchorHeadingDeg, now)` uses `GeoMath.destination` to compute positions and then forces safety markers on every record:

- `isTestData = true`, `dataSource = TEST_ONLY_SYNTHETIC`, `packageId = "test-only-synthetic"`
- `roadName = "TEST ONLY – <label>"`, `city = "Synthetic test area"`, `sourceReference = "Synthetic test fixture – not a real camera"`
- `confidenceScore = 0.9`, `lastVerifiedAt = now`

`parse()` refuses a file whose `_warning` lacks `TEST-ONLY` or whose ids do not start with `TEST-`. Sync never touches test packages (`syncInstalledPackages` skips `isTestData`), and `CameraRecordValidator` rejects `TEST_ONLY_SYNTHETIC` records from the server.

## 2. Scenarios and expected outcomes

Positions assume the tester drives straight along the anchor heading. Default settings: first warning 500 m, closer reminders on, unverified off.

| Id | Placement | Setup | Expected outcome (`CameraRelevanceEngine` / tracker) |
|---|---|---|---|
| **TEST-001** | 1500 m ahead | Fixed speed, same direction, 60 km/h, verified, main carriageway | **Alerts** at the 500 m band, then 200 m band (also 1000 m if selected). Passed silently afterwards. |
| **TEST-002** | 3000 m ahead, 25 m right | Fixed speed, **opposite direction** (180°) | **Never alerts** — `OPPOSITE_DIRECTION`. Counts as "nearby" (APPROACH mode) but never triggers CLOSE. |
| **TEST-003** | 4200 m ahead, 30 m right | Red-light, **crossing** (90°), junction | **Never alerts** — `CROSSING_ROAD`. |
| **TEST-004** | 5200 m ahead, 35 m left | Fixed speed, same direction, 40 km/h, **service lane** | **No alert** while on the main carriageway with accuracy < 20 m: beyond 400 m `LOW_CONFIDENCE`, within 400 m `DIFFERENT_ROAD` (35 m > 15 m + accuracy). Would alert only if the tester is actually in the service lane. |
| **TEST-005** | 800 m **behind** | Fixed speed, same direction | **Never alerts** — `BEHIND`. Within 5 km, so APPROACH mode is expected right after loading. |
| **TEST-006** | 6500 m ahead | ANPR, **both directions** (bearing 180°) | **Alerts** — axis match. No speed limit shown. |
| **TEST-007** | 7600 m ahead | User-reported, **pending verification** | **No alert** by default (`NOT_VERIFIED`). With *Include unverified reports* on: alerts with "unverified" wording (confidence −0.10). |
| **TEST-008** | 9000 m ahead | Speed + red-light, 50 km/h, junction | **Alerts** normally (junction is not an ambiguous context). |
| **TEST-009** | 10500 m ahead | Fixed speed, 70 km/h, **flyover** | **Late, single-band start**: `LOW_CONFIDENCE` beyond 400 m; first alert at ≤ 400 m with accuracy ≤ 20 m (announced with real distance, e.g. "400 m"), then the 200 m band. |
| **TEST-010** | 12000 m ahead | Bus-lane, **direction unknown**, community confirmed | **Alerts** with reduced confidence (direction score 0.5). |

The unit test asserts these reject reasons at representative distances (e.g. TEST-004 → `DIFFERENT_ROAD` 300 m before it; TEST-009 → `LOW_CONFIDENCE` at 800 m and alertable at 300 m).

## 3. Debug-only loading

1. Install a **debug** build (`com.drivealert.app.debug`). Release builds bind `NoOpTestDataTools`, and the asset lives only in `src/debug`.
2. Stand at the start of a long, straight, safe road (or a closed test area) facing the direction of travel.
3. Trigger `TestDataTools.loadSyntheticCamerasAround(lat, lon, headingDeg)` — entry point: **Settings → Developer (debug build only) → "Load TEST-ONLY cameras ahead of me"** (uses the current location and GPS heading; heading 0° = north if none). It upserts the 10 cameras with `replace_all`, registers the test package, and bumps `dataVersion` so a running session reloads its cache.
4. Camera Details shows a **TEST-ONLY** banner for these records; history rows start with "TEST ONLY –".
5. Remove with **"Remove test cameras"** → `clearSyntheticCameras()` (`deleteTestData()` on cameras and packages).

Tips: a passenger should operate the phone. A mock-location app (Developer options → *Select mock location app*) replaying a straight track can exercise the scenarios without driving; the app does not filter mock fixes, so remember to disable it afterwards.

## 4. Rules

- Never add real-world coordinates or real camera ids to this file; ids must start with `TEST-`.
- Never move the asset into `src/main`.
- New scenarios must document their expected outcome here and in `SyntheticCameraDatasetTest`.
