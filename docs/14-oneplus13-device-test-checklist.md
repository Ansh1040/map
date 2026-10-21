# 14 · OnePlus 13 Real-Device Test Checklist

Run on a **OnePlus 13** before each release candidate. A **passenger** operates the phone; the driver only drives. Use TEST-ONLY cameras ([11-mock-dataset.md](11-mock-dataset.md)) in a debug build, and real verified cameras in the release candidate where licensed data exists.

| Field | Value |
|---|---|
| Build / variant / versionName | |
| OxygenOS version / security patch | |
| Google Maps version | |
| Date / tester / route | |
| Battery setting under test | Allow / Optimise / Restrict |

Mark each row ☒ in **Pass** or **Fail** and add notes (time, distance spoken, screenshots).

## A. Install and first run

| # | Steps | Expected | Pass | Fail | Notes |
|---|---|---|---|---|---|
| A1 | Install APK/AAB (fresh), open | Onboarding starts (navy screens, step dots) | ☐ | ☐ | |
| A2 | Check merged permissions in *App info → Permissions* | Only Location, Notifications listed; no "All the time" option requested | ☐ | ☐ | |
| A3 | `adb shell dumpsys location` before starting alerts | No location request from Drive Alert | ☐ | ☐ | |

## B. Onboarding and permissions

| # | Steps | Expected | Pass | Fail | Notes |
|---|---|---|---|---|---|
| B1 | Read disclosure step | Disclosure appears **before** any system dialog; text covers purpose, Maps-in-front, on-device processing | ☐ | ☐ | |
| B2 | *Continue & allow location* → **Precise**, *While using the app* | Next step shown; Home has no location banner | ☐ | ☐ | |
| B3 | Reinstall → choose **Approximate** | Home: red "Precise location needed"; Calibration *Start Drive* disabled; service cannot start | ☐ | ☐ | |
| B4 | Reinstall → **Don't allow** twice | Banner remains; Fix path leads to App info (note behaviour) | ☐ | ☐ | |
| B5 | **Only this time** → start a drive → leave app | Alerts keep working while the session runs; asked again next launch | ☐ | ☐ | |
| B6 | Deny notifications (Android 13+) | Setup warning shown; during a drive voice still speaks, no heads-up | ☐ | ☐ | |
| B7 | Finish → *Open setup assistant* | Checklist reflects real state; each Fix opens the right screen | ☐ | ☐ | |

## C. Calibration and start

| # | Steps | Expected | Pass | Fail | Notes |
|---|---|---|---|---|---|
| C1 | Indoors: open Calibration | Spinner, then Weak/none; amber sky tip after ~8 s; *Start drive anyway* after 20 s | ☐ | ☐ | |
| C2 | Outdoors, open sky | Accuracy ≤ 20 m → Good/Excellent within ~30 s; *Start Drive* enabled | ☐ | ☐ | |
| C3 | Tap *Start Drive* | Returns to Home; "Camera alerts active" card; persistent notification with **Stop alerts** | ☐ | ☐ | |
| C4 | Leave Calibration without starting | `dumpsys location` shows the 1 s high-accuracy request removed | ☐ | ☐ | |

## D. Driving with Google Maps

| # | Steps | Expected | Pass | Fail | Notes |
|---|---|---|---|---|---|
| D1 | *Open Maps*, start navigation, drive toward TEST-001 / a verified camera | Heads-up appears **over Maps** ("Speed camera ahead · 500 m • Speed limit 60 km/h"); voice speaks; no full-screen takeover | ☐ | ☐ | |
| D2 | Continue | Second warning at the 200 m band; spoken distance ≈ real distance | ☐ | ☐ | |
| D3 | Pass the camera, return the same way within 30 min | No repeat for the passed camera | ☐ | ☐ | |
| D4 | Over-limit wording: approach TEST-008 (synthetic limit 50 km/h) on a road whose **real** posted limit is higher, driving legally at e.g. 55 km/h — never exceed a real limit for testing | Detail line / voice says "Please slow down" | ☐ | ☐ | |
| D5 | Screen locked (power button) during approach | Voice + lock-screen notification still arrive on time | ☐ | ☐ | |
| D6 | Maps closed, Drive Alert in background | Alerts still arrive (fallback / APPROACH / CLOSE modes) | ☐ | ☐ | |

## E. Road geometry

| # | Steps | Expected | Pass | Fail | Notes |
|---|---|---|---|---|---|
| E1 | Opposite carriageway (TEST-002 or a real divided road) | **No** warning | ☐ | ☐ | |
| E2 | Crossing road at a junction (TEST-003) | **No** warning | ☐ | ☐ | |
| E3 | Parallel/service road (TEST-004) driving on the main road | **No** warning | ☐ | ☐ | |
| E4 | Flyover camera (TEST-009) | Warning only within ~400 m with good GPS | ☐ | ☐ | |
| E5 | Turn off the road before a camera | At most one early warning; no repeat within 3 min | ☐ | ☐ | |

## F. Connectivity, GPS and power

| # | Steps | Expected | Pass | Fail | Notes |
|---|---|---|---|---|---|
| F1 | Mobile data off during drive | Alerts unaffected (offline database) | ☐ | ☐ | |
| F2 | Airplane mode with location/GPS on | Alerts unaffected | ☐ | ☐ | |
| F3 | Tunnel / underpass (≥ 10 s GPS loss) near a camera | Notification shows "GPS lost" only after 20 s; estimated warnings possible; no false "passed" | ☐ | ☐ | |
| F4 | **Power saving mode** on | Record whether warnings still arrive on time; setup assistant flags it | ☐ | ☐ | |
| F5 | Battery: *Allow background activity* | 60 min drive, no service stop | ☐ | ☐ | |
| F6 | Battery: *Optimise* (default) | 60 min drive, no service stop | ☐ | ☐ | |
| F7 | Battery: *Restrict background activity* | Setup assistant warns; record whether alerts stop; if killed, next app open shows "alerts stopped unexpectedly" | ☐ | ☐ | |

## G. Stationary, stop, lifecycle

| # | Steps | Expected | Pass | Fail | Notes |
|---|---|---|---|---|---|
| G1 | Slow traffic jam (crawling, stop-and-go) 15 min | **No** "Has your drive ended?" prompt | ☐ | ☐ | |
| G2 | Park, engine off, 10 min | Prompt notification with *End drive* / *Keep alerts on* (not full-screen) | ☐ | ☐ | |
| G3 | *Keep alerts on* | Prompt disappears; session continues | ☐ | ☐ | |
| G4 | Ignore prompt; wait 30 min parked | "Alerts stopped" notification; service and location requests gone | ☐ | ☐ | |
| G5 | *Stop alerts* from the notification | Notification removed; `dumpsys location` shows no requests | ☐ | ☐ | |
| G6 | *Stop alerts* in app | Same as G5 | ☐ | ☐ | |
| G7 | Swipe Drive Alert away from Recents during a drive | Alerts continue (foreground service); note any OxygenOS kill | ☐ | ☐ | |
| G8 | Swipe the session notification away (Android 14+ allows) | Session keeps running; reopen app to stop | ☐ | ☐ | |
| G9 | **Reboot** during an active session | After boot: **no** notification, **no** service, **no** location request; nothing restarts | ☐ | ☐ | |

## H. Audio, language, accessibility

| # | Steps | Expected | Pass | Fail | Notes |
|---|---|---|---|---|---|
| H1 | Bluetooth car audio + music playing | Music ducks (not paused) for chime/voice, then restores | ☐ | ☐ | |
| H2 | Maps voice and Drive Alert voice close together | Both intelligible; note overlap behaviour | ☐ | ☐ | |
| H3 | Phone call during a warning | Chime/voice skipped; heads-up still shown | ☐ | ☐ | |
| H4 | *Visual only* mode | Heads-up only; no sound/vibration | ☐ | ☐ | |
| H5 | App language **Hindi** (system per-app language) | UI in Hindi where translated; hi-IN voice if installed, else en-IN | ☐ | ☐ | |
| H6 | System **dark mode** | All screens legible; warning colours distinct | ☐ | ☐ | |
| H7 | **TalkBack** on: onboarding, Home, Calibration, Stop | All controls announced with role/state; gauge reads accuracy + quality | ☐ | ☐ | |
| H8 | Font size largest + display size large | No clipped text; buttons ≥ 48 dp | ☐ | ☐ | |

## Sign-off

| Result | Count |
|---|---|
| Pass | |
| Fail (blocking) | |
| Fail (non-blocking) | |

Tester: ____________ Date: ________ Release decision: ☐ Go ☐ No-go
