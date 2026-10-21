# Server API Contract

The camera-data backend is **not part of this repository**. This document is the contract the Android client implements in `network/CameraDataApi.kt` (Retrofit + kotlinx.serialization, `ignoreUnknownKeys = true`, `explicitNulls = false`).

> **Optional.** By default Drive Alert needs no server: camera data comes from the weekly OpenStreetMap snapshot and reports become public OpenStreetMap notes (opt-in). Implement this contract only to add an official or licensed data source with its own moderation, then set `CAMERA_API_BASE_URL`.

## 1. Conventions

- Base URL: `CAMERA_API_BASE_URL` (must start with `https://`; a trailing `/` is added). Unconfigured → the client makes **no** calls.
- JSON, `snake_case` field names, timestamps in epoch **milliseconds**, enums as upper-case names (see `domain/model/Camera.kt`).
- Timeouts: connect 15 s, read 30 s.
- **Privacy:** sync requests carry only package ids and data versions — never the driver's location. Only explicit user reports carry coordinates.

## 2. Endpoints

### `GET v1/packages` — catalog

Response: array of `PackageDto`.

```json
[
  { "id": "ka-bengaluru", "name": "Bengaluru", "state": "Karnataka", "city": "Bengaluru",
    "data_version": 1287, "camera_count": 412, "coverage_note": "Major arterials only" }
]
```

| Field | Type | Req. | Notes |
|---|---|---|---|
| `id` | string | ✓ | Stable package id |
| `name`, `state` | string | ✓ | Display |
| `city`, `coverage_note` | string | – | `coverage_note` is shown to users — state coverage limits honestly |
| `data_version`, `camera_count` | int | ✓ | Latest server version / size (informational for the catalog) |

All values in these examples are illustrative placeholders — no real dataset or camera is implied. Client: upserts the catalog, preserves local `is_installed` / `data_version`; packages dropped from the catalog are not deleted locally (**server must not silently drop installed packages** — publish a final `replace_all` with zero cameras instead).

### `GET v1/packages/{id}/delta?since={version}` — versioned delta

Response: `PackageDeltaDto`.

```jsonc
{ "package_id": "ka-bengaluru", "from_version": 1280, "to_version": 1287, "replace_all": false,
  "upserts": [ { "id": "cam-…", "latitude": <double>, "longitude": <double>, "type": "FIXED_SPEED",
                 "verification_status": "VERIFIED", "data_source": "OFFICIAL_GOVERNMENT",
                 "source_reference": "<licence / dataset reference>", "...": "..." } ],
  "removed_ids": ["cam-…"] }
```

| Field | Type | Req. | Notes |
|---|---|---|---|
| `package_id` | string | ✓ | Must equal the requested id, else the client ignores the response |
| `from_version`, `to_version` | long | ✓ | Client rejects `to_version` < local version; skips a no-op (same version, no changes) |
| `replace_all` | bool | – (false) | `true` → client deletes all rows of the package first, then applies upserts. **Use for `since=0` (first install) and when `since` is older than retained history.** |
| `upserts` | `CameraDto[]` | – | New or changed records |
| `removed_ids` | string[] | – | Tombstones (deleted in chunks of 500) |

The delta is applied in one Room transaction; the package then stores `to_version`.

#### `CameraDto`

| Field | Type | Req. | Client validation (`CameraRecordValidator`) |
|---|---|---|---|
| `id` | string | ✓ | blank → dropped |
| `latitude`, `longitude` | double | ✓ | out of range or exactly `0,0` → dropped |
| `type` | `CameraType` | ✓ | unknown → dropped |
| `verification_status` | `VerificationStatus` | ✓ | unknown or `REJECTED` → dropped |
| `data_source` | `DataSource` | ✓ | must be `OFFICIAL_GOVERNMENT`, `LICENSED_PROVIDER`, `ADMINISTRATOR` or `COMMUNITY_MODERATED` (`TEST_ONLY_SYNTHETIC` → dropped) |
| `source_reference` | string | **✓** | blank/missing → dropped; truncated to 200 |
| `road_name` / `city` / `state` | string | – | truncated to 120 / 80 / 80 |
| `bearing_deg` | double | – | outside 0–360 → null; null bearing forces `directionality = UNKNOWN` |
| `directionality` | enum | – (`UNKNOWN`) | `ONE_DIRECTION`, `BOTH_DIRECTIONS`, `UNKNOWN` |
| `speed_limit_kmh` | int | – | outside 5–150 → null |
| `verification_count` | int | – (0) | negative → 0 |
| `last_verified_at`, `expires_at` | long | – | expired rows stop alerting and are purged after each sync |
| `confidence_score` | double | – (0.5) | clamped to 0–1 |
| `is_active` | bool | – (true) | inactive rows are stored but never queried |
| `road_context` | enum | – (`UNKNOWN`) | `MAIN_CARRIAGEWAY`, `SERVICE_LANE`, `FLYOVER`, `UNDERPASS`, `PARALLEL_ROAD_NEARBY`, `JUNCTION`, `UNKNOWN` |
| `road_segment_id` | string | – | map-matching segment from a licensed provider |
| `elevation_m` | double | – | **WGS84 ellipsoidal height** (same reference as GNSS altitude) |

### `POST v1/reports` — community report

Request `ReportRequestDto`:

```jsonc
{ "client_report_id": "r-42-1760000000000", "type": "NEW_CAMERA", "camera_id": null,
  "latitude": <double>, "longitude": <double>, "bearing_deg": null, "speed_limit_kmh": null,
  "camera_type": "FIXED_SPEED", "note": "optional, ≤ 500 chars", "created_at": 1760000000000 }
```

`type` ∈ `NEW_CAMERA`, `CAMERA_REMOVED`, `INCORRECT_POSITION`, `INCORRECT_DIRECTION`, `INCORRECT_SPEED_LIMIT`, `TEMPORARY_MOBILE_CAMERA`, `DUPLICATE_CAMERA`, `ABUSE`. Coordinates are sent only for `NEW_CAMERA`, `TEMPORARY_MOBILE_CAMERA`, `INCORRECT_POSITION`.

Response `ReportResponseDto`: `{ "report_id": "srv-…", "status": "SUBMITTED" }` — `status` ∈ `SUBMITTED`, `UNDER_REVIEW`, `ACCEPTED`, `REJECTED`, `DUPLICATE`, `EXPIRED` (unknown → `SUBMITTED`).

`client_report_id` (`r-<localId>-<createdAt>`) is an **idempotency key per install**, not globally unique — the server must de-duplicate retries by it, scoped to the caller once authentication exists.

## 3. Status codes and client retry behaviour

| Response | Sync (`CameraSyncWorker`, ≤ 3 attempts) | Report upload (`ReportUploadWorker`, ≤ 5 attempts) |
|---|---|---|
| 2xx | apply | store `report_id` + status, audit `UPLOADED` |
| 429, 5xx, network error | retry with exponential backoff (30 min periodic / 1 min manual) | retry (5 min backoff) |
| other 4xx | fail (non-retryable) | mark `REJECTED`, audit `REJECTED_BY_SERVER` |
| malformed JSON | fail (non-retryable) | — |

Send `Retry-After` with 429; the client currently relies on WorkManager backoff.

## 4. Server responsibilities (not implemented in this repo)

- **Moderator review queue** for every report; crowdsourced data never becomes trusted automatically.
- **Audit history** of every record and moderation decision (who, when, why, source).
- **Abuse handling**: `ABUSE` reports, reporter reputation, bans; ≥ 3 abuse flags without moderator approval → `REJECTED`.
- **Confidence scoring** per `ReportModerationPolicy.confidenceScore` (reference implementation): base by source — official 0.95, licensed 0.90, administrator 0.85, community `0.30 + 0.08 × min(confirmations, 6) + 0.15 × reputation + 0.10 if moderator-approved`; minus `0.12 × disputes` and `0.20 × abuseFlags`; multiplied by `exp(−days × ln2 / 365)` (half-life one year). Example: community, 4 confirmations, reputation 0.7, approved, 30 days → 0.825 × 0.945 ≈ **0.78**.
- **Verification threshold** per `statusFor`: community records become `COMMUNITY_CONFIRMED` only with **≥ 3 distinct confirmations *and* moderator approval**; official/licensed/admin records need moderator approval for `VERIFIED`; disputes ≥ confirmations → `DISPUTED`.
- **Expiry**: temporary/mobile reports 4 h, other pending reports 30 days; set `expires_at` on mobile cameras.
- **Rate limiting server-side** (the client's 30 s / 5 per hour / 20 per day and 40 m / 24 h duplicate checks are advisory only).
- **Licensing and provenance**: every record carries `source_reference`; keep licence documents per source; honour takedown requests.
- Monotonic `data_version` per package; retain tombstones long enough for deltas or answer with `replace_all`.
- Data-subject requests (access, correction, erasure of reports) under the DPDP Act 2023.

## 5. Security

| Item | Status |
|---|---|
| HTTPS only | Enforced by `network_security_config.xml` (cleartext off, system CAs only) and `ApiConfig.isConfigured` |
| Authentication | **TODO** — none today; needed for per-user rate limiting, abuse handling and deletion requests (e.g. per-install token, optionally Play Integrity) |
| Certificate pinning | **TODO** — add OkHttp `CertificatePinner` with primary + backup pins in `AppModule.provideOkHttp` |
| Input validation | Client validates every record (§2); the server must validate reports equally |
| Location minimisation | No location in sync calls; report coordinates retained only as long as moderation requires |
