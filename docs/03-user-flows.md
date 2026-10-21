# 03 · User Flows

All flows reflect the current code; screen details are in [07-ui-specifications.md](07-ui-specifications.md).

## Google Maps limitation (honest wording — use in onboarding and store listing)

> **Drive Alert works alongside Google Maps but is not connected to it.** It never reads your Maps route, destination, screen or notifications. It uses your phone's own location and Drive Alert's own camera database. This means:
> - It warns about cameras on the road you are actually driving, whether or not Maps is navigating.
> - Warnings cannot appear *inside* the Google Maps screen. They appear as a short notification at the top of the screen and as a voice message.
> - It does not know which turn you will take next, so it may warn about a camera on a road you are about to leave.
> - Camera data may be incomplete or out of date. Always follow road signs and the posted speed limit.

---

## 1. First run: onboarding and permissions

Steps come from `OnboardingScreen` (`Step` enum). The prominent location disclosure is its own step and is shown **before** the system dialog.

```mermaid
flowchart TD
  S([First launch]) --> R{onboardingComplete?}
  R -- yes --> HOME[Home]
  R -- no --> W[Welcome]
  W --> HIW["How it works with Google Maps<br/>(limitation text above)"]
  HIW --> SAF["Safety: obey limits,<br/>don't touch phone while driving"]
  SAF --> LOC["Prominent disclosure:<br/>what location is used for,<br/>only while alerts are on,<br/>processed on device"]
  LOC -- "Continue & allow location" --> ACC[acceptDisclosure → DataStore]
  ACC --> SYS1{{"System dialog<br/>FINE + COARSE"}}
  LOC -- "Not now" --> N
  SYS1 -- "Precise / Approximate / Deny" --> N{Android 13+?}
  N -- yes --> NOT[Notifications step]
  NOT -- "Allow notifications" --> SYS2{{"System dialog<br/>POST_NOTIFICATIONS"}}
  NOT -- "Not now" --> DONE
  SYS2 --> DONE
  N -- no --> DONE[Done]
  DONE -- "Open setup assistant" --> SA[Home + Setup Assistant]
  DONE -- "Finish" --> HOME
```

Outcomes on Home: approximate-only → red banner "Precise location needed"; location services off → "Turn on"; other readiness warnings (notifications, background restriction, power saver) → amber "Review" banner → Setup Assistant.

## 2. Start a drive (calibration → service)

```mermaid
flowchart TD
  H[Home: Start Camera Alerts] --> P{Precise location?}
  P -- no --> REQ{{Runtime location request}} --> H
  P -- yes --> C[Calibration screen]
  C --> CHK["BatteryRestrictionChecker.check()"]
  CHK --> B1{Precise + location on?}
  B1 -- no --> BAN["Red banner + Allow / Turn on"]
  B1 -- yes --> G["High-accuracy 1 s updates<br/>(screen-scoped)"]
  G --> Q{"Accuracy<br/>≤10 Excellent · ≤20 Good<br/>≤50 Fair · else Weak"}
  Q -- "Fair or better" --> EN[Start Drive enabled]
  Q -- "Weak / none for 20 s" --> ANY[Start drive anyway enabled]
  EN --> ST[Tap → setLastCalibration, CameraAlertService.start]
  ANY --> ST
  ST --> FGS{"Service: precise?"}
  FGS -- no --> X[stopSelf, nothing starts]
  FGS -- yes --> F["startForeground(location)<br/>'Camera alerts active' + Stop"]
  F --> SM["DrivingSessionManager.start:<br/>TTS warm-up, PASSIVE + fallback,<br/>last-known fix ≤ 2 min"]
  SM --> BACK[Return to Home: active session card]
```

Calibration's 1 s high-accuracy request is removed as soon as the screen stops collecting (`WhileSubscribed(1 s)`).

## 3. Active drive with Google Maps in front

```mermaid
flowchart TD
  A[Home active card: Open Maps] --> M[Google Maps navigating in front]
  M --> FIX["Maps' GPS fixes reach Drive Alert's<br/>PASSIVE request (no extra GPS cost)"]
  FIX --> FB{"≥3 fixes in 30 s?"}
  FB -- yes --> OFF[Remove 45 s balanced fallback]
  FB -- "no data for 60 s" --> ON[Enable 45 s balanced fallback]
  OFF & ON --> E[Evaluate cameras within 5 km]
  E --> M3{"Any camera ≤ 5 km?"}
  M3 -- yes --> AP["APPROACH: balanced 12 s"]
  AP --> M4{"Relevant camera ahead<br/>≤ close-enter distance?"}
  M4 -- yes --> CL["CLOSE: high accuracy 2 s / 4 s"]
  CL --> AL["Heads-up over Maps + voice<br/>(ducks music)"]
  AL --> PASS[Camera passed → silent 30 min]
  PASS --> E
```

## 4. Alert sequence

```mermaid
sequenceDiagram
  participant T as SessionCameraTracker
  participant S as DrivingSessionManager
  participant C as AlertCoordinator
  participant N as CameraAlertNotifier
  participant V as Haptics / Chime / Voice
  participant H as AlertHistoryRepository
  T->>S: PendingAlert(camera, distance, band, first?)
  S->>S: round distance (50 m / 100 m steps), speed, over-limit flag
  S-)C: present(alert, settings)
  C->>N: heads-up (silent channel, 25 s timeout, no full-screen)
  alt visual-only mode
    C-->>S: done
  else
    C->>V: vibrate (first: 220-120-220 ms, reminder: 160 ms)
    C->>V: chime on navigation-guidance stream (skipped during calls)
    C->>V: TTS QUEUE_FLUSH, transient ducking focus
  end
  S-)H: record(cameraId, type, road, city, limit, distance, band, time)
```

## 5. Stationary, "Has your drive ended?" and auto-stop

```mermaid
stateDiagram-v2
  [*] --> Moving
  Moving --> Still: no move beyond 40 m + accuracy<br/>and speed < 2.5 m/s
  Still --> Moving: moved, or speed ≥ 2.5 m/s (accuracy ≤ 30 m)
  Still --> Stationary: 2 min
  Stationary --> Prompt: 10 min → notification<br/>[End drive] [Keep alerts on]
  Prompt --> Moving: movement detected (prompt hidden)
  Prompt --> Still: Keep alerts on (timer restarts)
  Prompt --> Stopped: End drive (USER_CONFIRMED_DRIVE_ENDED)
  Prompt --> AutoStopped: 30 min still → 'Alerts stopped' notification<br/>(STATIONARY_TIMEOUT)
  Stopped --> [*]
  AutoStopped --> [*]
```

Traffic jams: any displacement beyond the radius or a measured speed ≥ 2.5 m/s (9 km/h) restarts the clock, so stop-and-go traffic never reaches the prompt. While stationary, camera evaluation is skipped unless the position moved ≥ 10 m.

## 6. Stop flows

```mermaid
flowchart LR
  N1["Notification: Stop alerts<br/>(USER_NOTIFICATION)"] --> SS
  N2["App: Stop alerts<br/>(USER_IN_APP)"] --> SS
  N3["Prompt: End drive<br/>(USER_CONFIRMED_DRIVE_ENDED)"] --> SS
  N4["Auto-stop<br/>(STATIONARY_TIMEOUT)"] --> SS
  SS["CameraAlertService.stopSession"] --> A1["DrivingSessionManager.stop:<br/>cancel job, remove both location<br/>callbacks, quit looper, TTS shutdown"]
  A1 --> A2[Cancel alert + prompt notifications]
  A2 --> A3[sessionMarkedActive = false]
  A3 --> A4["stopForeground(REMOVE) + stopSelf"]
  K["System kills / destroys service"] --> D["onDestroy → stop(SERVICE_DESTROYED)"]
  D --> NX["Next app open: 'alerts stopped<br/>unexpectedly' banner → Setup Assistant"]
```

- **Swipe from Recents:** the foreground service keeps running (no `stopWithTask`); stop via the notification.
- **Reboot:** nothing restarts (no boot receiver; `START_NOT_STICKY`; a null restart intent calls `stopSelf()`).

## 7. Community report flow

```mermaid
flowchart TD
  D[Camera Details: Report update] --> R1["ReportScreen(cameraId)<br/>removed · position · direction ·<br/>speed limit · duplicate · abuse"]
  ST[Settings: Report new camera] --> R2["ReportScreen(null)<br/>new · temporary mobile"]
  R1 & R2 --> MV{"Session speed ≥ 10 km/h?"}
  MV -- yes --> PC["Warning + 'I am a passenger' required"]
  MV -- no --> F[Fill form]
  PC --> F
  F --> LOCQ["Location (if needed): cached ≤ 30 s<br/>or one balanced fix"]
  LOCQ --> SUB[Submit]
  SUB --> POL{"ReportModerationPolicy.checkSubmission"}
  POL -- Invalid --> E1[Red banner]
  POL -- RateLimited --> E2["'Try again in N min'"]
  POL -- Duplicate --> E3["Already known / reported"]
  POL -- Accepted --> Q["Room: PENDING_UPLOAD + audit CREATED"]
  Q --> CFG{API configured?}
  CFG -- no --> KEEP[Stays queued locally]
  CFG -- yes --> W["ReportUploadWorker → POST v1/reports"]
  W -- 2xx --> OK["status from server + audit UPLOADED"]
  W -- "IO / 5xx / 429" --> RETRY["retry (≤ 5 attempts, 5 min backoff)"]
  W -- "other 4xx" --> REJ["REJECTED + audit REJECTED_BY_SERVER"]
  OK --> MOD["Server moderation (not in repo).<br/>Alerts change only via a later package delta."]
```

## 8. Sync flow

```mermaid
flowchart TD
  APP["DriveAlertApp.onCreate"] --> PER["Periodic sync 24 h (flex 6 h)<br/>Wi-Fi only unless mobile data allowed<br/>battery not low, backoff 30 min"]
  UN["Data Packages: Update now"] --> MAN["One-time sync, any network"]
  PER & MAN --> W[CameraSyncWorker]
  W --> CFG{ApiConfig.isConfigured?}
  CFG -- no --> OK0[success, no-op]
  CFG -- yes --> CAT["GET v1/packages → upsert catalog<br/>(keeps local install state)"]
  CAT --> LOOP["For each installed, non-test package:<br/>GET v1/packages/:id/delta?since=version"]
  LOOP --> SAN{"Same id, version not older,<br/>not a no-op?"}
  SAN -- no --> LOOP
  SAN -- yes --> VAL["CameraRecordValidator drops invalid /<br/>unlicensed / REJECTED records"]
  VAL --> TX["Transaction: replace_all? delete package,<br/>delete removed_ids, upsert (chunks of 500)"]
  TX --> VER["dataVersion++ → running session<br/>invalidates NearbyCameraIndex"]
  VER --> PKG["Package: version, count, lastUpdated"]
  PKG --> PURGE[Purge expired cameras]
  W -- "IO / 5xx / 429" --> RT["Result.retry (≤ 3 attempts)"]
```

Only package ids and data versions are sent; the driver's location never is.
