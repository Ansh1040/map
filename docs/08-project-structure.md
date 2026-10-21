# 08 · Project Structure

Generated from a listing of the repository (build outputs, `.gradle/`, `.idea/` omitted). Single Gradle module `:app`, root project name `DriveAlert`.

```text
project_4/
├── README.md                         Overview, safety statement, docs index
├── docs/                             This documentation set (01–15, server-api-contract)
├── build.gradle.kts                  Root: plugin aliases only (apply false)
├── settings.gradle.kts               Repositories, rootProject "DriveAlert", include(":app")
├── gradle.properties                 4 GB heap, parallel + caching, AndroidX, non-transitive R
├── gradle/
│   ├── libs.versions.toml            Version catalog (AGP 8.13.2, Kotlin 2.2.21, Hilt 2.57.2, Room 2.8.3…)
│   └── wrapper/                      Gradle 8.14.3 wrapper
├── gradlew, gradlew.bat
├── local.properties                  sdk.dir + deployment keys — git-ignored, never commit
├── .gitignore                        build dirs, local.properties, keystores
└── app/
    ├── build.gradle.kts              SDK 26–36, BuildConfig keys, release R8, Room schema dir, test deps
    ├── proguard-rules.pro            kotlinx.serialization + Retrofit keeps; strips Log.v/d/i
    ├── schemas/                      (generated on first build) Room schema JSON — commit it
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml   Permissions, FGS type=location, no boot receiver, Maps key placeholder
        │   ├── res/
        │   │   ├── drawable/         ic_stat_camera, ic_action_stop, launcher fg/monochrome
        │   │   ├── mipmap-anydpi-v26/ adaptive launcher icon
        │   │   ├── values/           colors.xml, themes.xml, strings.xml (all UI, notification and voice text)
        │   │   ├── values-hi/        strings.xml — Hindi for the core driving flow (native review TODO)
        │   │   ├── xml/              network_security_config (HTTPS only), data_extraction_rules (no backup)
        │   │   └── resources.properties  unqualifiedResLocale=en (per-app language list)
        │   └── java/com/drivealert/app/
        │       ├── DriveAlertApp.kt        @HiltAndroidApp; channels, periodic sync, history prune
        │       ├── MainActivity.kt         Single activity, edge-to-edge, DriveAlertTheme
        │       ├── domain/                 PURE KOTLIN — no android.* imports
        │       │   ├── alerts/             AlertThresholdPolicy, SessionCameraTracker
        │       │   ├── cameras/            NearbyCameraIndex
        │       │   ├── geo/                GeoMath, GridCell
        │       │   ├── location/           TrackingModePolicy (+PassiveFallbackPolicy),
        │       │   │                       AdaptiveLocationController, StationaryDetector, DeadReckoning
        │       │   ├── model/              Camera, LocationModels, SessionModels, AlertSettings
        │       │   ├── ports/              Ports.kt (LocationSource, CameraLookup, AlertPresenter, …)
        │       │   ├── relevance/          MotionEstimator, BearingMatcher, CameraRelevanceEngine
        │       │   ├── reporting/          ReportModerationPolicy
        │       │   └── session/            DrivingSessionManager
        │       ├── alerts/                 AlertCoordinator, AlertTextFormatter, HapticAlerter
        │       ├── battery/                BatteryRestrictionChecker, OemBatteryGuide, SystemSettingsIntents
        │       ├── cameras/                CameraRepository / RoomCameraRepository
        │       ├── data/
        │       │   ├── history/            AlertHistoryRepository
        │       │   ├── sync/               CameraSyncRepository, CameraSyncWorker, SyncScheduler
        │       │   └── testdata/           SyntheticCameraDataset, TestDataTools interface
        │       ├── database/               Entities, Daos, Mappers, DriveAlertDatabase
        │       ├── di/                     AppModule, BindingsModule, StorageModule, @ApplicationScope
        │       ├── location/               FusedLocationSource, ForegroundLocationProbe, LocationMapping
        │       │   └── service/            CameraAlertService (foreground service)
        │       ├── navigation/             Routes (type-safe), DriveAlertNavHost / DriveAlertRoot
        │       ├── network/                CameraDataApi + DTOs, CameraRecordValidator, ApiConfig
        │       ├── notifications/          NotificationChannels, CameraAlertNotifier,
        │       │                           SessionNotificationFactory, NotificationIntents
        │       ├── permissions/            LocationPermissions
        │       ├── reporting/              ReportRepository, ReportUploadWorker
        │       ├── settings/               SettingsRepository (DataStore)
        │       ├── speech/                 VoiceAlertManager, AlertChimePlayer, AudioFocusHelper
        │       ├── ui/
        │       │   ├── RootViewModel.kt    Start destination (onboarding vs home)
        │       │   ├── theme/Theme.kt      Palette, typography, shapes, StatusColors
        │       │   ├── components/         BrandHeader, PrimaryActionButton, ToggleRow, InfoBanner, …
        │       │   ├── util/Formatting.kt  distance, date, direction formatting
        │       │   ├── onboarding/         OnboardingScreen + ViewModel
        │       │   ├── home/               HomeScreen, HomeViewModel, CameraMap
        │       │   ├── calibration/        CalibrationScreen + ViewModel
        │       │   ├── details/            CameraDetailsScreen
        │       │   ├── report/             ReportScreen + ViewModel
        │       │   ├── history/            HistoryScreen + ViewModel (local-only alert history)
        │       │   ├── settings/           SettingsScreen + ViewModel
        │       │   ├── setup/              SetupAssistantScreen (permissions, battery, OnePlus guide)
        │       │   ├── privacy/            PrivacyPolicyScreen (in-app policy summary)
        │       │   └── packages/           DataPackagesScreen + ViewModel (city/state packages)
        │       └── util/                   AppLog (privacy-safe), SystemClockImpl
        ├── debug/
        │   ├── assets/test_only_mock_cameras.json     TEST-ONLY synthetic scenario (offsets, not real places)
        │   └── java/…/debugtools/DebugTestDataTools.kt  Loads it around the tester; Hilt DebugToolsModule
        ├── release/
        │   └── java/…/debugtools/NoOpTestDataTools.kt   Release binding: never loads test data
        ├── test/                     JVM unit + Robolectric tests — see 10-testing-strategy.md
        │   ├── java/…/domain/        geo, relevance, alerts, location, cameras, session, reporting
        │   ├── java/…/database/      Room DAO/repository tests (Robolectric, in-memory)
        │   ├── java/…/notifications/ channel + notification content tests
        │   ├── java/…/location/service/  foreground-service lifecycle (Hilt + Robolectric)
        │   ├── java/…/testutil/      Fixtures (synthetic geometry), fakes
        │   └── resources/robolectric.properties
        └── androidTest/              Instrumented tests on OnePlus 13 / emulator (Room, foreground service)
```

## Source sets

| Source set | Contents | Notes |
|---|---|---|
| `main` | All production code and resources | Domain code must stay free of `android.*` imports. |
| `debug` | `assets/test_only_mock_cameras.json`, `DebugTestDataTools` + `DebugToolsModule` | Package `com.drivealert.app.debug` (`applicationIdSuffix ".debug"`), version suffix `-debug`. |
| `release` | `NoOpTestDataTools` + `ReleaseToolsModule` | R8 minify + resource shrinking; signing **TODO (required before release)**. Synthetic assets are not packaged. |
| `test` | JVM tests (JUnit 4, coroutines-test, Turbine, Robolectric, Room/Work testing) | `isIncludeAndroidResources = true`, `isReturnDefaultValues = true`. |
| `androidTest` | Instrumented tests (AndroidX Test runner/rules, Compose UI test, Room testing) | Runner `androidx.test.runner.AndroidJUnitRunner`. |

Both `debug` and `release` bind `TestDataTools` under the same Hilt component, so each build variant has exactly one binding.

## Naming conventions

- Domain policies are plain classes with a `*Config` data class of defaults (`TrackingConfig`, `RelevanceConfig`, …); tests override configs instead of mocking.
- Android adapters are `@Singleton` with `@Inject` constructors; ports are bound in `BindingsModule`.
- Enums are persisted and transmitted **by name** — never rename a constant without a migration.
