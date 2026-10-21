# 13 · Privacy and Google Play Compliance

Checklist for release reviews. "Code" = already implemented; **TODO** = must be done before release. This is engineering guidance, not legal advice — have counsel confirm the final answers.

## 1. Prominent disclosure (User Data policy)

- [x] Code: dedicated onboarding step (`OnboardingScreen` `Step.LOCATION`) shown during normal use, **before** the runtime dialog, stating what is accessed (precise location), why (camera warnings), when (while the app is open, to show the user on the map and count nearby cameras; after Start Drive also when the app is closed or not in use — e.g. Google Maps in front or the screen locked — until the drive ends), and that it is processed on the device and not sold or shared.
- [x] Code: acceptance stored (`locationDisclosureAccepted`); the button that accepts also launches the request.
- [ ] **TODO**: Home / Calibration "Allow" banners must re-show the disclosure when it was skipped ("Not now") instead of calling the runtime request directly.
- [ ] **TODO**: Final disclosure copy in `strings.xml` (English + Hindi) reviewed by legal.

## 2. Data safety form (suggested answers)

| Data type | Collected (leaves device)? | Shared? | Notes |
|---|---|---|---|
| Precise location — map, calibration, alerts | **No** — processed on the device only, never transmitted by the app | No | Fixes live only in memory; history stores no driver coordinates. Note: map tiles for the area shown (initially around the user) are fetched from OpenFreeMap. |
| Precise location — community reports | **Yes, optional and user-initiated**: in the default build an opted-in report publishes one position (the phone's position for new/temporary/wrong-position reports, otherwise the camera's) as a **public OpenStreetMap note** | **Yes — published publicly** to OpenStreetMap at the user's request | Purpose: App functionality (community data correction). Not used for ads or analytics. Declare as collected (optional) and shared. |
| Other user-generated content (report note, ≤ 500 chars) | Yes, optional | **Yes — published publicly as an OpenStreetMap note at the user's request** | Same per-report consent as the report location |
| Personal info, contacts, device IDs, advertising ID | No | No | No accounts, no ad SDKs, `client_report_id` is not a device identifier |
| Crash logs / diagnostics | No (no third-party SDK) | No | Play Console vitals are collected by Google, not the app |
| App activity / alert history | No (local only) | No | |

- Data **not sold**; **no ads**; data **encrypted in transit** (HTTPS only).
- [x] Reports are kept on the phone unless the user ticks "Publish publicly on OpenStreetMap" (off by default); old unconsented reports are never published. Debug builds publish only to the OSM **test** server and never for TEST-ONLY cameras or mock positions.
- [ ] Published OSM notes are public and cannot be deleted from the app; state this in the Data safety "deletion" answer. Deletion of local data: Settings → Delete local history.

## 3. Foreground service and location declarations

- [x] Code: `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`, `android:foregroundServiceType="location"`, `startForeground(…, FOREGROUND_SERVICE_TYPE_LOCATION)` only after a user tap on a visible screen.
- [x] Code: persistent notification with **Stop alerts** for the entire session; `START_NOT_STICKY`; no boot receiver.
- [ ] **TODO**: Play Console → *App content → Foreground service permissions*: declare `FOREGROUND_SERVICE_LOCATION`, use case "user-initiated, navigation-related camera warnings while driving".
- [ ] **TODO**: Record the **justification video** (≤ 30–60 s, real device): onboarding disclosure → grant → Calibration → *Start Drive* → notification visible → switch to Google Maps → heads-up + voice warning (TEST-ONLY data in a debug-signed build or a real verified camera) → *Stop alerts* from the notification.
- [x] No *background location* declaration needed: `ACCESS_BACKGROUND_LOCATION` is never requested.

## 4. Permissions

| Declared | Not declared (deliberately) |
|---|---|
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `VIBRATE`, `INTERNET`, `ACCESS_NETWORK_STATE` | `ACCESS_BACKGROUND_LOCATION`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SYSTEM_ALERT_WINDOW`, `USE_FULL_SCREEN_INTENT`, `RECEIVE_BOOT_COMPLETED`, accessibility service, notification listener |

Re-check the **merged** manifest of every release (`app/build/intermediates/merged_manifests/…`) — libraries must not add permissions.

## 5. Transport and storage security

- [x] `network_security_config.xml`: cleartext blocked for all domains; only **system** CAs trusted (user-installed CAs are not).
- [x] Network calls in the default build: GitHub (weekly-refreshed camera file, ~daily check, no location), OpenFreeMap (map tiles while the map is visible), OpenStreetMap API (only opted-in reports; the OSM Foundation stores the sender's IP with the note). `ApiConfig.isConfigured` (optional server) requires an `https://` base URL.
- [ ] **TODO**: OkHttp `CertificatePinner` for the production host (`AppModule.provideOkHttp`), with a backup pin.
- [ ] **TODO**: API authentication (none today).
- [x] `allowBackup="false"`, `fullBackupContent="false"`, `data_extraction_rules.xml` excludes root, database, sharedpref and file domains from cloud backup and device transfer.
- [x] No driver coordinates in `alert_history`; no trip/route/fix tables (see [04-database-schema.md](04-database-schema.md)).

## 6. User control and deletion

- [x] Code: history retention 30 days (pruned at app start); `AlertHistoryRepository.clear()`; `ReportRepository.deleteAllLocalReports()`.
- [x] Settings → **Delete local history** removes alert history and saved reports; History → **Clear** removes alert history; uninstall removes all local data.
- [ ] **TODO**: "Stop alerts" and "Revoke location" explained in the privacy policy.

## 7. Logging

- [x] `AppLog`: `d` logs only in debug builds; R8 strips `Log.v/d/i` in release; warnings/errors log exception class names only.
- [x] No coordinates logged anywhere; `AppLog.redact()` coarsens to 0.1° in debug and returns `redacted` in release.
- [ ] Keep it that way: code review rule — never log `LocationFix`, camera coordinates near the user, or report contents.

## 8. Camera data licensing

- [x] Default source is **OpenStreetMap**, licensed under the **ODbL 1.0**. Attribution "© OpenStreetMap contributors" appears in camera details, the data-packages screen, Settings → Open data and licences, a permanently visible credit on the map, [NOTICE](../NOTICE) and the Play listing.
- [x] The bundled snapshot is a Derivative Database; it is published in this repository under the ODbL, together with the script that produces it (`tools/fetch_osm_cameras.py`).
- [x] Contributor user names/ids are stripped from the snapshot; only camera tags needed for alerts are kept.
- [x] Apps download one weekly snapshot from GitHub instead of querying Overpass, respecting the volunteer servers' usage policy.
- [x] Map tiles: OpenFreeMap (free, no key) with a visible "© OpenMapTiles © OpenStreetMap contributors" credit; renderer MapLibre (BSD-2) whose licence text and bundled third-party notices ship in the app (Settings → Open-source licences).
- [x] Never derive camera data from Google Maps or other apps' content.
- [x] Community reports become public OSM notes only with explicit per-report consent, and fall under OSM's terms.
- [ ] If an official/licensed source is added later (`CAMERA_API_BASE_URL`), keep `source_reference` on every record (`CameraRecordValidator` enforces it).

## 9. India-specific considerations

- **Digital Personal Data Protection Act, 2023** (and the DPDP Rules, 2025, being phased in — confirm current applicability with counsel):
  - Notice in clear, plain language (English and Hindi at minimum) describing the personal data and purpose before consent.
  - Consent by clear affirmative action (the disclosure button; a per-report checkbox for publishing), withdrawable as easily as given (Stop alerts, revoke permission, delete local reports). A report already published as an OSM note cannot be withdrawn from the app.
  - Purpose limitation and data minimisation — alerts on-device; reports only what is needed.
  - Erase personal data when the purpose is served (30-day history; report expiry 4 h / 30 days on the server).
  - Grievance contact: GitHub Issues (see [PRIVACY.md](../PRIVACY.md)); no server-side personal data exists in OpenStreetMap mode (published OSM notes are public by the user's choice).
  - Breach notification duty applies only if a custom server is operated (`CAMERA_API_BASE_URL`).
  - Target audience 18+ (drivers) — avoids children's-data obligations; set the Play target age accordingly.
- **Road safety law**: handheld phone use while driving is an offence; the app is designed for a mounted phone and voice output and blocks reporting while moving unless a passenger confirms.
- [ ] **TODO**: Legal review that announcing enforcement-camera locations is permitted in each launch state, with positioning as a compliance reminder.

## 10. Safety disclaimers (in app and listing)

"Camera data may be incomplete or out of date and Drive Alert cannot guarantee that every camera is included. Always obey road signs and posted speed limits. Do not operate your phone while driving." Shown in onboarding (safety step), Home footer, Camera Details, and the store description.

## 11. Restricted content and store listing wording

Drive Alert must not promote or facilitate illegal activity.

| Use | Avoid |
|---|---|
| "Speed-limit and camera reminders", "drive within the limit", "road-safety alerts" | "Avoid fines / challans", "beat the cameras", "never get caught" |
| "Works alongside Google Maps" (descriptive, no Google logos) | Implying Google endorsement or integration |
| "Coverage depends on available licensed data for your city" | "All cameras in India", "100 % accurate", "nationwide coverage" |
| Neutral camera icons, map, calm blue palette | Radar, police, racing, stealth or speed-trap imagery |
| "Processes location on your phone" | "Anonymous", "no data ever" (reports do upload data) |

Screenshots: use TEST-ONLY data in a debug build labelled as sample data, or real licensed data — never invented "real" locations.
