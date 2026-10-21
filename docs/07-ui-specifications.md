# 07 · UI Specifications

Jetpack Compose + Material 3. Theme: `ui/theme/Theme.kt`; shared components: `ui/components/Components.kt`.

## 1. Design tokens

### Palette (dynamic colour is **off** so warning colours stay meaningful)

| Token | Light | Dark | Use |
|---|---|---|---|
| `primary` | Electric blue `#1F6BFF` | `#8DB4FF` | Primary buttons, links, selected states |
| `onPrimary` | `#FFFFFF` | `#002E6B` | |
| `primaryContainer` / `on…` | `#DCE6FF` / `#001A41` | `#0E47B8` / `#DCE6FF` | Camera-type badge |
| `secondary` | Navy `#0A1F44` | `#B8C7E8` | "Stop alerts" button (`destructive = true`) |
| `tertiary` | Amber `#F2A100` | `#FFC54D` | Accent |
| `background` | Card tint `#F3F6FC` | `#0B1220` | Screen background |
| `surface` | `#FFFFFF` | `#121A2B` | Cards |
| `surfaceVariant` / `onSurfaceVariant` | `#E7ECF5` / `#475069` | `#1F2940` / `#BAC3D8` | Neutral chips, secondary text |
| `surfaceContainerHigh` | `#EFF3FA` | `#1C263B` | Next-camera row |
| `outline` / `outlineVariant` | `#8A93A8` / `#D5DBE8` | `#8590A8` / `#2E3850` | Dividers |
| `error` / `onError` | Red `#D93025` / `#FFFFFF` | `#FFB4AB` / `#690005` | Errors only |
| `StatusColors.header` / `onHeader` / `onHeaderMuted` | Navy `#0A1F44` / `#FFFFFF` / `#B9C6E4` | `#061430` / `#FFFFFF` / `#9FB0D6` | Brand header, onboarding, top bars |
| `good` / `onGood` | `#E3F4E8` / `#0D652D` | `#173B24` / `#9BE0B0` | GPS ready, verified |
| `warn` / `onWarn` | `#FFF1D6` / `#7A4F00` | `#3D2E0A` / `#FFD27A` | Setup warnings, weak GPS, pending |
| `bad` / `onBad` | `#FCE4E2` / `#A50E0E` | `#4A1512` / `#FFB4AB` | Blocking problems |
| `BrandColors.Success` | `#1E8E3E` | same (unthemed) | Active-session dot, good GPS gauge |
| `BrandColors.Amber` | `#F2A100` | same (unthemed) | Weak GPS gauge; heads-up accent (`alert_amber`) |

Red and amber are reserved for genuine warnings; never decorative.

### Typography, shape, spacing

| Item | Spec |
|---|---|
| Type scale | Material 3 defaults; `headlineMedium` 28 sp, `headlineSmall` 24 sp, `titleLarge` 22 sp, `titleMedium` 16 sp — all **SemiBold**; `labelLarge` (buttons) **16 sp SemiBold**; `DisplayNumberStyle` 44 sp Bold / 48 sp line height (accuracy gauge) |
| Shapes | extraSmall 8 · small 12 · **medium 16** (buttons, banners, fields) · **large 24** (cards) · extraLarge 28 · header bottom corners 28 dp |
| Spacing | 16 dp screen gutter and card gap · 16 dp card padding · header 20 dp × 16 dp · 8 dp between banners · button padding 20 × 14 dp |
| Touch targets | ≥ **48 dp** everywhere; **primary action 60 dp** (`PrimaryActionButton`); toggle and nav rows 64 dp; search-result and nearby-camera rows 56 dp; report radio rows 52 dp; "Open Maps" 52 dp; segmented buttons 48 dp |

### Accessibility, dark mode, content rules

- Every status uses **icon + text + colour** (never colour alone). Decorative icons have `contentDescription = null`; actionable icons are labelled (`cd_back`, `cd_clear_search`, `cd_show_on_map`). The "Cameras near you" card title is marked as a heading.
- Gauge exposes a combined description (`cd_accuracy_gauge`); segmented distance buttons expose `stateDescription`; toggles use `Role.Switch`, report rows `Role.RadioButton`, passenger confirmation `Role.Checkbox`.
- Supports font scaling (scrollable columns, no fixed-height text), RTL (`supportsRtl`), TalkBack, and system dark theme (`isSystemInDarkTheme()`).
- **No racing, radar, "stealth", police or chequered-flag imagery or copy.** Camera icons are neutral Material symbols: Speed, CameraAlt, Traffic, FlashOn, DocumentScanner, DirectionsBus, Block, Campaign.
- **No full-screen interruptions while driving**: no full-screen intents, overlays, dialogs or auto-opening activities during a session. Warnings are heads-up notifications + audio.

---

## 2. Screens

### 2.1 Onboarding — `ui/onboarding/OnboardingScreen.kt`

Full-bleed navy (`header`) background; step dots (active 24 × 8 dp pill); 72 dp primary-colour circle icon; headline (onHeader) + body (onHeaderMuted); white bullet card; bottom: primary button 60 dp + "Not now" text button 48 dp.

```
┌──────────────────────────────┐
│ ● ━━ ● ● ● ●                  │  Steps: Welcome · How it works with Maps ·
│  (◯ icon 72dp)                │  Safety · Location disclosure ·
│  Your position on the map;    │  Notifications (13+) · Done
│  alerts after Start Drive     │
│ ┌──────────────────────────┐ │  Disclosure bullets: purpose; works with Maps
│ │ ⓘ Works with Maps in front│ │  in front / screen locked; processed on device;
│ │ ⓘ Processed on this phone │ │  not shared or sold; stop any time
│ └──────────────────────────┘ │
│ [   Continue & allow location ] 60dp
│          Not now              │
└──────────────────────────────┘
```

### 2.2 Home — `ui/home/HomeScreen.kt`

```
┌──────────────────────────────┐
│ Drive Alert                   │ navy header, rounded 28dp bottom
│ <tagline>                     │
│ [Q  Search road or city     ] │ search ≥ 2 chars, 8 results
└──────────────────────────────┘
 [!] Precise location needed [Allow]   banners: red (blocking) / amber (Review)
┌──────────────────────────────┐
│        Map 240dp             │ first fix frames you + the 3 nearest cameras
│   (12)      ▲       ●        │ (12) cluster up to zoom 11, tap = zoom in 2 levels · ● you
│                              │ ▲ watched direction, only where OSM records one
│                              │ markers: blue trusted · violet pending · red disputed;
│                              │ cameras of the list below: never clustered, drawn on top
│ (GPS ready) (12 cameras nearby)│ chips; "No camera data" if DB empty
└──────────────────────────────┘
 [ (>)  Start Camera Alerts     ] 60dp  → Calibration
┌──────────────────────────────┐
│ (near) Cameras near you      │ hidden during a drive and when location is off or not permitted;
│                              │ shown once a fix ≤ 5 min old is read and the list computed
│ (◯) Fixed speed camera  (pin)│ row ≥ 56dp → Camera Details
│     2.3 km north-east of you │ + " • 60 km/h" when the limit is known
│     MG Road                  │ road name, if any; (pin) 48dp = show on map
│ ──────────────────────────── │ up to 10 rows, nearest first, within 25 km
│ [Show all 10]                │ first 5 rows; then "Show all N" / "Show fewer"
└──────────────────────────────┘
┌──────────────────────────────┐
│ (vol) Voice alerts       [on] │ 64dp row
│ (dist) First warning: 500 m   │
│ [ 200 m | 500 m | 1 km ]      │ segmented, 48dp
└──────────────────────────────┘
 ⓘ Camera data may be incomplete or outdated. Always obey road signs.
```

**Active session card** replaces the Start button: green dot + "Camera alerts active"; mode line (passive / approaching / close); next-camera row (badge, title, "500 m • Speed limit 60 km/h", taps to Camera Details); "Current speed 48 km/h" + "Drive safely"; outlined **Open Maps** (52 dp); **Stop alerts** (60 dp, navy). The map is MapLibre with OpenFreeMap vector tiles (no API key); attribution is an always-visible credit that links to the OpenStreetMap copyright page, plus MapLibre's attribution dialog. Offline it shows "Map needs an internet connection" — alerts still work.

**Map markers and framing.** The map (`ui/home/CameraMap.kt`) receives every usable camera of the installed packages (trusted, plus unverified reports when the user opts in), up to 5,000 (`CameraRepository.allActive()`, `MAX_MAP_CAMERAS = 5_000`). They are reloaded when the camera data or the include-unverified setting changes. Nothing is hidden when zoomed out: there is no zoom cut-off and no query for the visible area. Two GeoJSON sources draw the cameras. A clustered source holds every camera except those in the "Cameras near you" list: clusters form up to zoom 11 with a 45 px radius and show a count bubble, and tapping one zooms in two levels. An unclustered source holds the cameras in the "Cameras near you" list (up to 10), always drawn as single markers on top, never inside a cluster. Single markers keep the trust colours (blue = trusted, violet = pending, red = disputed). Where OpenStreetMap records a camera's direction (`Camera.bearingDeg`), a white 14 dp arrow shows the direction of travel the camera watches, double-headed when it watches both directions. Cameras without direction data have no arrow; today that is most of them (142 of 579 cameras in the bundled snapshot have an arrow, about 1 in 4). On the first location fix the map frames the user's position together with the 3 nearest cameras, with 48 dp padding, never closer than zoom 15. If the nearest cameras are not known yet it waits up to 1 s for them; with no camera within 25 km it centres on the user at zoom 13. After that it keeps the position the user left it at.

**Cameras near you** card, below the Start Camera Alerts button, so the list never pushes Start or Stop off screen. It is hidden while a drive is active: Home reads the position only when it opens, and during a drive the active-session card shows the live next camera. It is also hidden when location is off or not permitted. It is shown once a position no more than 5 minutes old has been read and the list computed for it; while a new reading is taken for an older position the card stays hidden, so stale lines never show. When a drive ends, Home reads the position again before showing the list: immediately if Home is on screen, otherwise on the next return to Home. Home never reads location in the background. Title row: near-me icon (primary) + "Cameras near you" (`titleMedium`). Up to 10 cameras within 25 km, nearest first (`NearbyCameras.rank`; unverified reports only when the user opts in), separated by dividers. The first 5 are shown; a text button "Show all N" / "Show fewer" expands or collapses the rest. Each row (≥ 56 dp) shows the type badge, the camera type name (`bodyLarge`), "2.3 km north-east of you" (`bodyMedium`, plus " • 60 km/h" when the speed limit is known) and the road name if any (`bodySmall`, muted). Distances are rounded as in voice alerts (50 m steps below 1 km, 100 m above) and the direction is an 8-point compass word, the same wording as "From your position" on Camera Details. Tapping a row opens Camera Details; the trailing pin button (48 dp, "Show on map") moves the map to that camera and scrolls the map card fully into view, below the status bar. A note replaces the rows in these cases: without precise location permission, "Turn on precise location to see how far cameras are from you."; with a fix worse than ±200 m, "Your position is accurate only to ±N m, not enough to show distances."; with no camera data installed at all, a line that says so and points to Settings → Camera data packages; with data but no camera within 25 km, "No mapped cameras within 25 km. OpenStreetMap coverage is still limited in some states."

### 2.3 Location Calibration — `ui/calibration/CalibrationScreen.kt`

```
┌ ← Check GPS ─────────────────┐
│ [!] Precise location needed   │ (only when blocking)
│ ┌──────────────────────────┐ │
│ │        ╭──────╮           │ │ 200dp ring, 12dp stroke
│ │        │ 8 m  │           │ │ 44sp number
│ │        │GPS accuracy│     │ │
│ │        ╰──────╯           │ │
│ │        Excellent          │ │ ≤10 Excellent · ≤20 Good · ≤50 Fair · Weak
│ │ <why calibration helps>   │ │
│ └──────────────────────────┘ │
│ (sun) Weak signal? Move into open sky   (amber, Weak or nothing after 8 s)
│ ⓘ Set up Maps before driving; don't touch the phone while moving
│ [ (car)  Start Drive         ] │ enabled at Fair+, or "Start drive anyway" after 20 s
└──────────────────────────────┘
```

Colours: Excellent/Good `Success`, Fair `primary`, Weak `Amber`, none `outline` (indeterminate spinner).

### 2.4 Active alert notification (heads-up) — `notifications/CameraAlertNotifier.kt`

```
┌──────────────────────────────────────────┐
│ (cam) Drive Alert                         │ small icon ic_stat_camera, amber accent
│ Speed camera ahead                        │ title: AlertTextFormatter.title(type)
│ 500 m • Speed limit 60 km/h               │ body
│ Current speed 48 km/h — Drive safely      │ expanded (BigText) detail
└──────────────────────────────────────────┘
```

Channel `camera_alerts` (IMPORTANCE_HIGH, silent — audio is played by the app), `CATEGORY_NAVIGATION`, `VISIBILITY_PUBLIC` (visible on lock screen), auto-cancel, **times out after 25 s**, one notification id (a new warning replaces the old). Over the limit → "Please slow down"; unverified records add an "unverified report" suffix. Never a full-screen intent. Persistent session notification (channel `session_status`, low importance): "Camera alerts active" · status line · **Stop alerts** action.

### 2.5 Camera Details — `ui/details/CameraDetailsScreen.kt`

```
┌ ← Camera details ────────────┐
│ (!) TEST-ONLY data (if test)  │
│ ┌──────────────────────────┐ │
│ │ (◯) Speed camera          │ │ 44dp badge + type + road
│ │     MG Road               │ │
│ │ (✓ Verified)              │ │ chip: Verified / Community confirmed (good),
│ └──────────────────────────┘ │ Pending / Disputed (warn)
│ Location · Coordinates · From your position ("2.3 km north-east of you")
│ Direction ("Northbound (12°)") · Speed limit · Last verified
│ Confirmations · Data source · Expires
│ ⓘ disclaimer                  │
│ [ (flag) Report an update     ] │ 60dp → Report Update
└──────────────────────────────┘
```

**From your position** (after Coordinates): distance and 8-point compass direction from the user to the camera, e.g. "2.3 km north-east of you" (`formatBearingFromYou`; great-circle distance and initial bearing from `GeoMath`, distance rounded with `AlertThresholdPolicy.roundForAnnouncement`: 50 m steps below 1 km, 100 m above). The position comes from a single read when the screen opens (`ForegroundLocationProbe.currentLocation`, accepting a cached fix up to 2 minutes old, otherwise one balanced-power fix). The row is shown only with precise location permission and a fix of ±200 m or better, and is hidden otherwise.

### 2.6 Report Update — `ui/report/ReportScreen.kt`

```
┌ ← Report ────────────────────┐
│ (car) You're moving. Only report as a passenger.   (amber, speed ≥ 10 km/h)
│ ☐ I am a passenger            │ 48dp
│ What changed?                 │
│ ◉ Camera removed  ○ Wrong position  ○ Wrong direction
│ ○ Wrong speed limit  ○ Duplicate  ○ Spam/abuse   (existing camera)
│ ○ New camera  ○ Temporary mobile camera          (from Settings)
│ [Fixed speed][Red light][ANPR]   (new/temporary)  │
│ Correct speed limit [   ] km/h   (speed limit)    │
│ Location 12.97160, 77.59460 [◎ Use current location]
│ Note (optional, ≤ 500)        │
│ ⓘ Reports are reviewed before they change any alert
│ [ (send) Submit              ] │
└──────────────────────────────┘
```

In OpenStreetMap mode a consent checkbox **"Publish publicly on OpenStreetMap so volunteers can review it"** (off by default) decides between a public OpenStreetMap note without an account name (OSM stores the sender's IP) and keeping the report on the phone; the checkbox is hidden for TEST-ONLY cameras. Outcome banners: published as OSM note / saved on this phone only / sent to server (custom server mode) (good); four WARN-tone "Saved on this phone only: …" banners when publishing was requested but blocked — TEST-ONLY camera, simulated (mock) position, accuracy worse than ±30 m, wrong-position report more than 300 m from the mapped camera; rate-limited "try again in N min", duplicate (warn); invalid (bad).

### 2.7 History — `ui/history/HistoryScreen.kt`

Navy header "History" / "Warnings from your recent drives, stored only on this phone", with a **Clear** action (confirmation dialog — allowed because it is never needed while driving). List from `AlertHistoryRepository.observeHistory(200)`, newest first; each card: type badge, alert title, road • city, "Alert at 500 m • <date, time>"; tap → Camera Details. Empty state: history icon + "No warnings yet…". No location trail is stored; entries older than 30 days are pruned at app start.

### 2.8 Settings — `ui/settings/SettingsScreen.kt`

Sections: **Alerts** — Voice alerts, Alert sound, Vibration (all disabled while visual-only is on), Silent visual-only mode, "Alert distance 500 m" segmented 200 m / 500 m / 1 km with the speed/GPS note, Closer reminders, Include unverified reports. **Camera data** — Camera data packages →, Update camera database (downloads the weekly OpenStreetMap snapshot; shows "Database last updated …"), Update on mobile data (re-schedules the periodic sync), Report a new camera →. **Device** — Setup assistant →, Notification settings →, App language → (system per-app language screen). **Privacy** — Privacy policy →, Delete local history (alert history **and** saved reports, confirmation dialog). **Developer (debug build only)** — TEST-ONLY warning banner, "Load TEST-ONLY cameras ahead of me", "Remove TEST-ONLY cameras". **About** — full data disclaimer, **Open data and licences** (opens openstreetmap.org/copyright), **Source code** (GitHub), version. Debug builds also show **Simulate a test drive (mock GPS)** with live progress and **Open Developer options**. Transient result banners (sync requested, history deleted, test data loaded…) auto-dismiss after 4 s.

### 2.9 Setup Assistant — `ui/setup/SetupAssistantScreen.kt`

Re-checks `DeviceReadiness` on every resume. **Required**: Location permission (Allow), Precise location (Open settings), Location services (Turn on). **Reliability**: Notifications incl. the camera-warnings channel (Allow / notification settings), Background activity restriction (Open settings), Power Saving Mode (Open battery-saver settings), Battery optimisation (informational — states that unrestricted access is *not* required; no button). Each row: ✓ / ! / ✕ icon with content description, title, one-line explanation, tonal action button ≥ 48 dp. **Your phone**: OEM card from `OemBatteryGuide` (OnePlus: "Settings → Battery → More settings → App battery management → Drive Alert → Allow background activity", note that wording differs between OxygenOS releases and not to enable High Performance mode) + "Open Drive Alert app settings". Footer: Android does not let apps change battery settings themselves.

### 2.10 Privacy Policy — `ui/privacy/PrivacyPolicyScreen.kt`

Nine cards (summary, location, on your phone, server communication, camera reports, what Drive Alert never does, retention, your choices, contact), always available offline. A "Read the full policy online" row appears only when `BuildConfig.PRIVACY_POLICY_URL` is set (defaults to [PRIVACY.md](../PRIVACY.md) on GitHub; contact via GitHub Issues).

### 2.11 Data Packages — `ui/packages/DataPackagesScreen.kt`

Coverage banner ("Coverage is limited to the cities and states listed… does not claim nationwide accuracy"). Source banner "Camera data: © OpenStreetMap contributors, open data under the ODbL…" and an **Update camera data now** row. One card per package from `CameraSyncRepository.observePackages()`: city/state title, install switch (`setPackageInstalled`, then `SyncScheduler.syncNow()`), coverage note, camera count, "Database last updated …". The TEST-ONLY synthetic package is shown with its switch disabled. One package per state / union territory (36), all installed by default from the bundled snapshot; switching one on works offline.

## 3. Review-round changes (v0.2)

- **Map card**: a permanently visible credit "© OpenMapTiles © OpenStreetMap contributors" at the bottom-right (tap → openstreetmap.org/copyright), plus MapLibre's attribution dialog. The "you are here" dot uses the Home screen's single location fix (no continuous GPS). The map keeps its position when the user navigates back, rotates or scrolls away; marker taps use a 48 dp target and pick the nearest marker; LazyColumn items have stable keys so search results no longer rebuild the map.
- **Report Update**: "Use my current location" takes a fresh high-accuracy fix and shows "±N m accuracy" (red above 30 m). For *Incorrect position* a hint says to stand where the camera really is. The publish checkbox is hidden for TEST-ONLY cameras. Outcome banners also explain when a report was **kept on this phone** instead of published: TEST-ONLY camera, simulated (mock) position, accuracy worse than ±30 m, or a wrong-position report more than 300 m from the mapped camera.
- **Settings → About → Open-source licences** (new screen `ui/licenses/LicensesScreen.kt`): monospace text of every file in `assets/licenses` (overview with data attribution, Apache-2.0, MapLibre Native Android with its third-party notices, MapLibre Java/GeoJSON MIT, MapLibre Gestures BSD-2).
- **Simulated test drive** row shows *Starting…* and then live progress; while starting or running it becomes **Stop simulated drive** (tap to cancel or stop), so a drive cannot be started twice. When the drive ends or is stopped, mock GPS is switched off and the alert session started for it ends (the "Camera alerts active" notification disappears by itself).
- Debug builds are labelled **Drive Alert (debug)** in the launcher and in Developer options → Select mock location app.
