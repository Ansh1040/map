# 05 · Algorithms

Every constant below is the default in the named `*Config` data class (all in `domain/`). Pseudocode explains intent; the Kotlin source is authoritative.

## 1. Camera relevance pipeline (`CameraRelevanceEngine.evaluate`)

```text
for camera in NearbyCameraIndex.candidatesNear(fix, searchRadiusM = 5 000 m):
    skip unless camera.isUsable(now)            # active, not expired, not REJECTED
    skip if distance > 5 000 m
    statusAllowed = VERIFIED | COMMUNITY_CONFIRMED  or (includeUnverified and not REJECTED)
    if heading == null -> NO_HEADING (approach candidate only if ≤ 1 500 m)
    position  = GeoMath.relativePosition(fix, heading, camera)   # along/cross-track
    direction = BearingMatcher.matchDirection(...)
    road      = roadDecision(camera, RoadContextProvider?.currentRoad(fix))
    reject in this order: OPPOSITE_DIRECTION, CROSSING_ROAD, DIFFERENT_ROAD (segment),
                          BEHIND, NOT_VERIFIED, POOR_GPS (> 100 m), NOT_AHEAD,
                          ambiguous-context checks, lateral offset, LOW_CONFIDENCE (< 0.55)
    otherwise -> isAlertable = true
```

Each `CameraEvaluation` carries two verdicts: **`isAlertable`** (strict — warn now) and **`isApproachCandidate`** (lenient — worth precise GPS: allowed status and roughly ahead within ±75°).

### 1.1 Heading and speed (`MotionEstimator`, `MotionConfig`)

| Source | Condition | Quality |
|---|---|---|
| Fix bearing (GNSS course) | speed ≥ 2.5 m/s and bearing accuracy ≤ 30° (or not reported) | `HIGH` |
| Chord between fixes | displacement ≥ max(20 m, 1.5 × (acc₁ + acc₂)) | `MEDIUM` if ≥ 2 × that, else `LOW` |

Heading and speed expire after 120 s (passive fixes can be 60–90 s apart). Chord speed = displacement / Δt when the fix has no speed.

### 1.2 Direction match (`BearingMatcher.matchDirection`, `BearingConfig`)

Tolerance `tol` = 45° (`HIGH`), 52.5° (`MEDIUM`), 60° (`LOW`). `diff` = smallest angle between heading and camera bearing.

| `Directionality` | `MATCH` | `OPPOSITE` | `CROSSING` |
|---|---|---|---|
| `ONE_DIRECTION` | diff ≤ tol | diff ≥ 180° − tol | otherwise |
| `BOTH_DIRECTIONS` | min(diff, 180° − diff) ≤ tol | — | otherwise |
| `UNKNOWN` / no bearing | → `DirectionMatch.UNKNOWN` (not rejected, lower confidence) | | |

`OPPOSITE` handles the **opposite carriageway** of a divided road; `CROSSING` handles a red-light camera on the **crossing road** of a junction. Both are hard rejections.

### 1.3 Ahead / beside / behind (`BearingMatcher.classifyAhead`)

```text
behindMargin = max(15 m, accuracy × 0.5)
if alongTrack < −behindMargin            -> BEHIND
if distance ≤ accuracy + 10 m            -> BESIDE   (too close to judge)
withinCone = |relativeAngle| ≤ strictCone(distance)
withinLane = alongTrack > 0 and |crossTrack| ≤ lateralTolerance
alongTrack > 0 and (withinCone or withinLane) -> AHEAD, else BESIDE / BEHIND
```

Strict cone: ≥ 600 m → 35°, ≥ 200 m → 45°, ≥ 60 m → 60°, < 60 m → 75°.

**Lateral tolerance** (`lateralToleranceM`): normal = 25 m + accuracy + 0.15 × distance (road curvature); ambiguous context = 15 m + accuracy (no curvature allowance). Example at 100 m with 8 m accuracy: 48 m normal, 23 m ambiguous. For normal contexts the lateral offset may reject a camera only within **150 m** (`lateralRejectDistanceM`), where cross-track is meaningful → `DIFFERENT_ROAD` (parallel road).

### 1.4 Ambiguous road contexts

`RoadContext.SERVICE_LANE`, `FLYOVER`, `UNDERPASS`, `PARALLEL_ROAD_NEARBY` are ambiguous unless map matching returns the same segment. Such cameras alert only when **all** hold: distance ≤ **400 m**, fix accuracy ≤ **20 m**, |cross-track| ≤ 15 m + accuracy; and they take −0.10 confidence. Further out they remain approach candidates (precise GPS is switched on) but reject as `LOW_CONFIDENCE`. Consequence: the first warning for these cameras fires at ≤ 400 m whatever band is selected, announced with the real distance.

### 1.5 Elevation

If the camera has `elevation_m` (WGS84 ellipsoidal) and the fix has altitude with vertical accuracy ≤ 6 m: mismatch when |altitude − elevation| > 10 m + vertical accuracy → **−0.35** confidence (flyover above vs. road below).

### 1.6 Map-matching hook (`RoadContextProvider`)

Optional port; **not configured** (`roadContextProvider = null` in `AppModule`). It must use a licensed road-data source and never read another app's route or screen.

| Result | Effect |
|---|---|
| Same `segmentId` | +0.15 and clears ambiguity |
| Different `segmentId` | hard reject `DIFFERENT_ROAD` |
| Same normalised road name | +0.05 |
| Different road name | −0.20 |
| No match / no data | no effect |

### 1.7 Confidence (`CameraRelevanceEngine.confidence`)

```text
score = 0.30 × camera.confidenceScore
      + 0.20 × heading   (HIGH 1.0 · MEDIUM 0.75 · LOW 0.45)
      + 0.20 × direction (MATCH 1.0 · UNKNOWN 0.5)
      + 0.20 × geometry  = max(1 − |angle| / strictCone, 1 − |crossTrack| / lateralTolerance)
      + 0.10 × gps       (≤ 10 m 1.0 · ≤ 25 m 0.8 · ≤ 50 m 0.5 · else 0.2)
      − 0.10 if ambiguous context     − 0.35 if elevation mismatch
      − 0.10 if not VERIFIED/COMMUNITY_CONFIRMED
      ± road-match adjustments (1.6), then clamp to [0, 1]; alertable if ≥ 0.55
```

| Example | Score |
|---|---|
| Verified (0.9), HIGH heading, MATCH, 500 m ahead, 3° off-axis (cross-track 26 m, tolerance 108 m), accuracy 8 m: 0.27 + 0.20 + 0.20 + 0.20 × 0.933 + 0.10 | **0.957** ✓ |
| Unknown direction, record confidence 0.5, LOW heading, 400 m, 5° off, accuracy 30 m: 0.15 + 0.09 + 0.10 + 0.178 + 0.05 | **0.568** ✓ (barely) |
| Same but `PENDING_VERIFICATION` and opted in: −0.10 | **0.468** ✗ `LOW_CONFIDENCE` |

## 2. Alert thresholds (`AlertThresholdPolicy`, `ThresholdConfig`)

Bands: `AlertDistances.thresholdsFor(primary, closerReminders)` → primary 1000 → [1000, 500, 200]; 500 → [500, 200]; 200 → [200] (only the primary when reminders are off). The smallest band is the **final** band.

```text
base    = band                                   (non-final)
        = max(band, speed × 8 s)                 (final: ≥ 8 s of travel)
trigger = base + speed × (1.5 s + interval / 2) + min(accuracy, 50 m) / 2
fires only if fix.accuracy ≤ clamp(band × 0.35, 35 m, 100 m)   → 1000: 100 m, 500: 100 m, 200: 70 m
```

`interval` = `AdaptiveLocationController.expectedIntervalSeconds` (CLOSE 2 s or 4 s, APPROACH 12 s, PASSIVE 45 s). Announced distance = `roundForAnnouncement(actual)`: 50 m steps below 1 km, 100 m steps above (minimum one step). The **real** distance is spoken, never the nominal band.

### Worked examples (accuracy 10 m)

**500 m band at 50 km/h** (bands [500, 200]): 13.89 m/s < 13.9 m/s → slow CLOSE interval 4 s.
trigger = 500 + 13.89 × (1.5 + 2) + 5 = 500 + 48.6 + 5 = **553.6 m**. With one fix every 4 s (55.6 m) the alert fires between ≈ 498 and 554 m → spoken "500 m" or "550 m". CLOSE mode had begun at max(1000, 500 + 13.89 × 18 + 150) = **1000 m**.

**200 m final band at 100 km/h**: 27.78 m/s → fast interval 2 s. base = max(200, 27.78 × 8 = 222.2) = 222.2; lead = 27.78 × 2.5 = 69.4; trigger = 222.2 + 69.4 + 5 = **296.7 m** ≈ 10.7 s before the camera → "300 m". Accuracy must be ≤ 70 m.

| Speed | CLOSE interval | 1000 band | 500 band | 200 final | Time at 200 final | CLOSE starts (primary 500 / 1000) |
|---|---|---|---|---|---|---|
| 30 km/h | 4 s | 1034 m | 534 m | 234 m | 28.1 s | 1000 / 1300 m |
| 50 km/h | 4 s | 1054 m | 554 m | 254 m | 18.3 s | 1000 / 1400 m |
| 60 km/h | 2 s* | 1047 m | 547 m | 247 m | 14.8 s | 1000 / 1450 m |
| 80 km/h | 2 s | 1061 m | 561 m | 261 m | 11.7 s | 1050 / 1550 m |
| 100 km/h | 2 s | 1074 m | 574 m | 297 m | 10.7 s | 1150 / 1650 m |
| 120 km/h | 2 s | 1088 m | 588 m | 355 m | 10.6 s | 1250 / 1750 m |

\* 60 km/h (16.67 m/s) is inside the 13.9–16.7 m/s hysteresis band and keeps the current interval (fast at session start).

**Multi-band crossing** (`SessionCameraTracker`): all un-fired bands whose trigger is reached are collected; **one** alert is issued for the closest band and all larger bands are consumed. Session started 400 m before a camera at 50 km/h → 500 band crossed (553.6), 200 band not (253.6) → one alert "400 m", the 200 band fires later. Started 230 m away → both crossed → a single first-time alert for the 200 band.

## 3. Duplicate suppression, passing, abandon, re-arm (`SessionCameraTracker`, `TrackerConfig`)

| Rule | Value |
|---|---|
| A band fires once per approach | `firedThresholds` per camera |
| **Passed** — behind | along-track < −(20 m + accuracy/2) **and** the closest approach was ≤ 150 m (`passProximityM`) — a camera that is merely "behind" after turning off the road is *not* passed |
| **Passed** — receding | closest approach ≤ 80 m and distance > closest + 40 m + accuracy/2 |
| Pass requires | the camera was alertable before (`wasAhead`), real (not dead-reckoned) fix, accuracy ≤ 50 m |
| After pass | silent for **30 min** (`rearmAfterPassMs`), excluded from mode decisions; then state resets (return trip) |
| **Abandon** (turned off before the camera) | 3 evaluations with `BEHIND` / `NOT_AHEAD` / `CROSSING_ROAD` / `OPPOSITE_DIRECTION` / `DIFFERENT_ROAD` while distance > closest + 100 m → bands cleared, camera not suppressed |
| Re-approach after abandon | allowed, but never within **3 min** of the previous alert for that camera |

GPS jitter cannot repeat a band (it is consumed), and a jitter-induced "behind" cannot pass a camera with poor accuracy.

## 4. Adaptive location modes (`TrackingModePolicy`, `TrackingConfig`)

| Mode | `TrackingMode` | Requests (`LocationPlan`) | Enter | Leave |
|---|---|---|---|---|
| 1 | `OFF` | none, no service | alerts off | — |
| 2 | `PASSIVE` | `PRIORITY_PASSIVE` 10 s (min 5 s) **+** fallback `BALANCED` 45 s (min 15 s; 90 s when stationary) only while passive data is stale | session start; no camera ≤ 5 km | — |
| 3 | `APPROACH` | `BALANCED` 12 s (min 5 s), no passive | any eligible, un-passed camera ≤ **5 000 m** (any direction) | > **5 500 m** |
| 4 | `CLOSE` | `HIGH_ACCURACY` **2 s** (≥ 16.7 m/s ≈ 60 km/h) / **4 s** (< 13.9 m/s = 50 km/h), min 1 s | approach candidate ≤ `closeEnter` | > `closeEnter + 400 m` |

```text
closeEnter = max(1 000 m, primaryBand + speed × 12 s × 1.5 + 150 m)
```

So the first warning is never missed between two 12 s APPROACH fixes (at 100 km/h a fix can arrive 333 m late: 1650 − 333 = 1317 m > 1074 m trigger). Stationary ≥ 120 s drops one level (CLOSE → APPROACH → PASSIVE). `AdaptiveLocationController` re-issues requests only when the resulting plan differs (`planChanges` counter) and ignores dead-reckoned fixes.

## 5. Passive fallback (`PassiveFallbackPolicy`)

```text
startSession      -> fallback ON  (no passive data known yet)
onEnterPassive    -> fallback OFF (give another app's fixes a chance)
onTick            -> if OFF and no fix for 60 s        -> ON
onFix             -> if ON and ≥ 3 fixes within 30 s   -> OFF  (another app, e.g. Maps, is producing GPS)
```

With Google Maps navigating, Drive Alert normally pays for no GPS of its own outside camera zones.

## 6. Stationary detection, traffic jams, prompt, auto-stop (`StationaryDetector`, `StationaryConfig`)

- Anchor resets when displacement > 40 m + max(anchor accuracy, fix accuracy), **or** speed ≥ 2.5 m/s with accuracy ≤ 30 m (crawling traffic counts as moving).
- `isStationary` after **2 min**; then camera evaluation is skipped unless the fix moved ≥ 10 m since the last evaluation.
- **10 min** → `AlertPresenter.showDriveEndCheck()` ("Has your drive ended?" with *End drive* / *Keep alerts on*). Movement hides it; *Keep alerts on* restarts the timer.
- **30 min** → `SessionEvent.AutoStopRequested(STATIONARY_TIMEOUT)` → service shows "alerts stopped" and stops.
- Without fresh fixes, stationary time is extrapolated at most 3 min past the last fix. Durations use `elapsedRealtime` (counts deep sleep).

## 7. Dead reckoning in tunnels (`DeadReckoning`, `DeadReckoningConfig`)

On each 3 s tick in `CLOSE` mode: if the last real fix is 6–45 s old, a heading is known and speed ≥ 3 m/s, predict along the heading at the last speed; accuracy = last accuracy + 0.15 × distance travelled. Estimated fixes **may** trigger warnings but never mark a camera passed, update heading, the stationary timer or the tracking mode.

Example at 80 km/h, last accuracy 5 m: 6 s → 133 m, 25 m; 20 s → 444 m, 71.7 m (200 m band gate of 70 m now fails); 28.5 s → 633 m, 100 m (`POOR_GPS` from here); estimation stops at 45 s.

## 8. Spatial index refresh (`NearbyCameraIndex`, `IndexConfig`)

Cache radius **12 km**; reload when the driver is > **4 km** from the load point (≈ every 2.4 min at 100 km/h), on `dataVersion` change (sync, test data) and at session start (`clear()`). Query radius must be ≤ 12 − 4 = 8 km, guaranteeing full coverage between reloads. `loadCount` counts database loads.

## 9. Session timing (`SessionConfig`)

Tick 3 s in CLOSE, 15 s otherwise · last-known location used at start if ≤ 2 min old · GPS `LOST` after 20 s without a fix (APPROACH/CLOSE) or 150 s (PASSIVE) · `WEAK` when accuracy > 30 m.

## 10. Known limitations

- The stationary power downgrade (§4) and the 90 s stationary fallback interval are applied only when `DrivingSessionManager.evaluateLocked()` runs; while stationary, fixes closer than 10 m to the last evaluation skip that call, so with low GPS jitter the previous mode can persist while parked. Candidate fix: call `locationController.update()` on the skip path or from the tick.
- No map matching is configured, so flyover / service-lane cameras rely on §1.4 heuristics only.
