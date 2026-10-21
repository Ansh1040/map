# 15 · Battery Profiling Plan and Report Template

Goal: measure what Drive Alert adds to a drive on a **OnePlus 13**, verify that the adaptive modes behave as designed ([05-algorithms.md](05-algorithms.md) §4–5), and catch regressions between releases.

## 1. Methodology

**Preparation**

- Build: release configuration (R8, no debug logging) signed with a local test key; repeat key scenarios on the debug build only when you need the `FusedLocationSource` plan log (debug package `com.drivealert.app.debug`).
- Phone: battery ≥ 90 %, **not charging** — connect with *Wireless debugging* (`adb pair` / `adb connect`) so USB power does not mask drain. Fixed brightness (50 %), adaptive brightness off, same SIM/data state, Bluetooth state recorded, same Google Maps version, ambient temperature noted.
- Same route, time of day and mount position for every compared run. Two runs per scenario minimum.

**Tools**

| Tool | Command / use |
|---|---|
| Reset stats at scenario start | `adb shell dumpsys batterystats --reset` |
| Per-app stats at scenario end | `adb shell dumpsys batterystats --charged com.drivealert.app > bs_<scenario>.txt` |
| Battery Historian | `adb bugreport bugreport_<scenario>.zip` → open in Battery Historian: GPS row, wakeups, CPU, jobs, per-app estimated mAh |
| Active location requests | `adb shell dumpsys location` (snapshot in each mode: package, priority, interval) |
| Service state | `adb shell dumpsys activity services com.drivealert.app` |
| Doze / power | `adb shell dumpsys deviceidle`, `adb shell dumpsys power` |
| Android Studio Power Profiler | Use when the device exposes on-device power rails (ODPM). If the OnePlus 13 shows no rails, rely on batterystats / Historian estimates and note it. |
| Plan log (debug) | `adb logcat -v time -s FusedLocationSource > plan_<scenario>.txt` |

## 2. Scenarios

| Id | Scenario | Duration | Expected modes |
|---|---|---|---|
| S0 | **Baseline**: Google Maps navigating, Drive Alert alerts **off** | 60 min | OFF (no requests from Drive Alert) |
| S1 | Alerts on, Maps navigating, **no camera within 5 km** of the route | 60 min | PASSIVE; fallback switches off once Maps fixes arrive |
| S2 | Alerts on, **Maps closed**, no cameras | 60 min | PASSIVE + 45 s balanced fallback |
| S3 | Alerts on, Maps navigating, **dense-camera city route** (licensed data or TEST-ONLY scenario) | 60 min | PASSIVE ↔ APPROACH ↔ CLOSE cycles |
| S4 | Alerts on, **parked / stationary** (engine off) | 30 min | Prompt at 10 min, auto-stop at 30 min |
| S5 (opt.) | Highway 90–110 km/h with cameras | 60 min | CLOSE entered 1.15–1.65 km before cameras |

## 3. Expected location requests per mode (from `TrackingModePolicy`)

| Mode | Passive request | Active request |
|---|---|---|
| OFF | — | — |
| PASSIVE | `PRIORITY_PASSIVE` 10 s (min 5 s) | `BALANCED` 45 s (min 15 s) only while passive data is stale; 90 s if stationary |
| APPROACH | — | `BALANCED` 12 s (min 5 s) |
| CLOSE | — | `HIGH_ACCURACY` 2 s (≥ ~60 km/h) or 4 s (< 50 km/h), min 1 s |

## 4. Metrics and targets

Targets are engineering goals to validate, not product claims.

| Metric | Source | Target |
|---|---|---|
| Incremental drain vs S0 (S1) | batterystats / Historian, % and mAh per hour | ≤ 1 %/h |
| Incremental drain (S2, Maps closed) | same | ≤ 2 %/h |
| Incremental drain (S3, dense cameras) | same | ≤ 3 %/h |
| Drain while parked (S4) | same | ≤ 0.5 %/h; service gone at 30 min |
| GPS-on time attributed to Drive Alert | Historian GPS row / batterystats per-UID GPS time | S1 ≈ 0; S3 ≈ time inside camera zones only |
| High-accuracy request duration | plan log timestamps (`active=HIGH_ACCURACY`) | ≈ 70–110 s per camera at 50 km/h (CLOSE from 1 000 m to pass) |
| Wakeups / wakelocks | Historian | No app-held partial wakelocks (the app acquires none) |
| Location request changes | `planChanges` / plan log lines | ≤ ~6 per camera approach; ≤ 10/h with no cameras |
| Spatial-index DB loads | `NearbyCameraIndex.loadCount` | ≈ 1 + km driven / 4 (+1 per data sync) |
| CPU time (user + system) | batterystats per-UID | Record; investigate > 60 s/h |
| Network during drive | batterystats per-UID mobile/Wi-Fi bytes | ≈ 0 (sync is deferred WorkManager work) |

## 5. Reading the app counters

**`AdaptiveLocationController.planChanges`** — incremented whenever the controller applies a `LocationPlan` that differs from the previous one: mode change, passive-fallback toggle, CLOSE 2 s ↔ 4 s switch, or stationary flag change. Reset on every session start. In debug builds each change produces exactly one `FusedLocationSource` log line such as `plan=CLOSE passive=null active=HIGH_ACCURACY/2000`, so `grep -c "plan=" plan_<scenario>.txt` ≈ `planChanges`. Many lines with no camera nearby indicates churn (check hysteresis, GPS jitter around 5 km / speed around 50–60 km/h).

**`NearbyCameraIndex.loadCount`** — incremented on each database load of the 12 km cache: once at session start, again after every 4 km travelled, and after each `dataVersion` change (sync or test-data load). It is **not logged or shown in the UI** today: read it from the `DrivingSessionManager` simulation test, or with the debugger during a debug-build drive. (Suggested follow-up: a debug-only diagnostics row showing `mode`, `planChanges`, `loadCount`, `cachedCameraCount`.)

## 6. Report template

### Environment

| Field | Value |
|---|---|
| App version / variant / commit | |
| OxygenOS version, battery health | |
| Google Maps version | |
| Battery setting (Allow / Optimise / Restrict), Power saving on/off | |
| Route, distance, time of day, weather / temperature | |
| Brightness, Bluetooth, mobile data | |

### Results per scenario

| Scenario | Run | Start % | End % | Δ %/h | App mAh (est.) | Incremental vs S0 | GPS-on (app) | CPU time | Notes |
|---|---|---|---|---|---|---|---|---|---|
| S0 | 1 | | | | | — | | | |
| S0 | 2 | | | | | — | | | |
| S1 | 1 | | | | | | | | |
| S2 | 1 | | | | | | | | |
| S3 | 1 | | | | | | | | |
| S4 | 1 | | | | | | | | |

### Mode time breakdown (from plan log)

| Scenario | PASSIVE (fallback off) | PASSIVE (fallback on) | APPROACH | CLOSE 4 s | CLOSE 2 s | planChanges | loadCount |
|---|---|---|---|---|---|---|---|
| S1 | | | | | | | |
| S2 | | | | | | | |
| S3 | | | | | | | |

### Location requests observed (`dumpsys location`) vs expected (§3)

| Mode | Observed priority / interval | Matches? |
|---|---|---|
| PASSIVE | | ☐ |
| APPROACH | | ☐ |
| CLOSE | | ☐ |
| After Stop | none | ☐ |

### Findings and actions

| # | Finding | Impact | Action / owner |
|---|---|---|---|
| 1 | | | |

Known item to watch: while parked, the stationary power downgrade may not apply if GPS jitter stays under 10 m (see [05-algorithms.md](05-algorithms.md) §10) — compare CLOSE time in S4 when parked next to a camera.
