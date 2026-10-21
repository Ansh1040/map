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
| Touch targets | ≥ **48 dp** everywhere; **primary action 60 dp** (`PrimaryActionButton`); toggle and nav rows 64 dp; report radio rows 52 dp; "Open Maps" 52 dp; segmented buttons 48 dp |

### Accessibility, dark mode, content rules

- Every status uses **icon + text + colour** (never colour alone). Decorative icons have `contentDescription = null`; actionable icons are labelled (`cd_back`, `cd_clear_search`).
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
│  Location is used only while  │  Notifications (13+) · Done
│  camera alerts are on         │
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
│        Google Map 240dp       │ markers: azure verified · violet pending
│                               │ · rose disputed; hidden below zoom 9
│ (GPS ready) (12 cameras nearby)│ chips; "No camera data" if DB empty
└──────────────────────────────┘
 [ (>)  Start Camera Alerts     ] 60dp  → Calibration
┌──────────────────────────────┐
│ (vol) Voice alerts       [on] │ 64dp row
│ (dist) First warning: 500 m   │
│ [ 200 m | 500 m | 1 km ]      │ segmented, 48dp
└──────────────────────────────┘
 ⓘ Camera data may be incomplete or outdated. Always obey road signs.
```

**Active session card** replaces the Start button: green dot + "Camera alerts active"; mode line (passive / approaching / close); next-camera row (badge, title, "500 m • Speed limit 60 km/h", taps to Camera Details); "Current speed 48 km/h" + "Drive safely"; outlined **Open Maps** (52 dp); **Stop alerts** (60 dp, navy). Map placeholder ("Map unavailable") when `MAPS_API_KEY` is missing — alerts still work.

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
│ Location · Coordinates · Direction ("Northbound (12°)")
│ Speed limit · Last verified · Confirmations · Data source · Expires
│ ⓘ disclaimer                  │
│ [ (flag) Report an update     ] │ 60dp → Report Update
└──────────────────────────────┘
```

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

Outcome banners: queued (good) / saved offline (server not configured) / rate-limited "try again in N min" / duplicate / invalid (bad).

### 2.7 History — `ui/history/HistoryScreen.kt`

Navy header "History" / "Warnings from your recent drives, stored only on this phone", with a **Clear** action (confirmation dialog — allowed because it is never needed while driving). List from `AlertHistoryRepository.observeHistory(200)`, newest first; each card: type badge, alert title, road • city, "Alert at 500 m • <date, time>"; tap → Camera Details. Empty state: history icon + "No warnings yet…". No location trail is stored; entries older than 30 days are pruned at app start.

### 2.8 Settings — `ui/settings/SettingsScreen.kt`

Sections: **Alerts** — Voice alerts, Alert sound, Vibration (all disabled while visual-only is on), Silent visual-only mode, "Alert distance 500 m" segmented 200 m / 500 m / 1 km with the speed/GPS note, Closer reminders, Include unverified reports. **Camera data** — Camera data packages →, Update camera database (shows "Database last updated …" or "Camera-data server not configured"), Update on mobile data (re-schedules the periodic sync), Report a new camera →. **Device** — Setup assistant →, Notification settings →, App language → (system per-app language screen). **Privacy** — Privacy policy →, Delete local history (alert history **and** saved reports, confirmation dialog). **Developer (debug build only)** — TEST-ONLY warning banner, "Load TEST-ONLY cameras ahead of me", "Remove TEST-ONLY cameras". **About** — full data disclaimer and version. Transient result banners (sync requested, history deleted, test data loaded…) auto-dismiss after 4 s.

### 2.9 Setup Assistant — `ui/setup/SetupAssistantScreen.kt`

Re-checks `DeviceReadiness` on every resume. **Required**: Location permission (Allow), Precise location (Open settings), Location services (Turn on). **Reliability**: Notifications incl. the camera-warnings channel (Allow / notification settings), Background activity restriction (Open settings), Power Saving Mode (Open battery-saver settings), Battery optimisation (informational — states that unrestricted access is *not* required; no button). Each row: ✓ / ! / ✕ icon with content description, title, one-line explanation, tonal action button ≥ 48 dp. **Your phone**: OEM card from `OemBatteryGuide` (OnePlus: "Settings → Battery → More settings → App battery management → Drive Alert → Allow background activity", note that wording differs between OxygenOS releases and not to enable High Performance mode) + "Open Drive Alert app settings". Footer: Android does not let apps change battery settings themselves.

### 2.10 Privacy Policy — `ui/privacy/PrivacyPolicyScreen.kt`

Nine cards (summary, location, on your phone, server communication, camera reports, what Drive Alert never does, retention, your choices, contact), always available offline. A "Read the full policy online" row appears only when `BuildConfig.PRIVACY_POLICY_URL` is set (**TODO (required before release)**: host the policy, set the URL, and fill in the grievance contact).

### 2.11 Data Packages — `ui/packages/DataPackagesScreen.kt`

Coverage banner ("Coverage is limited to the cities and states listed… does not claim nationwide accuracy"). If `ApiConfig.isConfigured` is false: warning banner "Camera-data server not configured…"; otherwise a "Refresh package list" row. One card per package from `CameraSyncRepository.observePackages()`: city/state title, install switch (`setPackageInstalled`, then `SyncScheduler.syncNow()`), coverage note, camera count, "Database last updated …". The TEST-ONLY synthetic package is shown with its switch disabled. "Update now" lives in Settings.
