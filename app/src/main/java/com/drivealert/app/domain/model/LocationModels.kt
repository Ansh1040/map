package com.drivealert.app.domain.model

/** Platform-independent location sample. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy radius (68% confidence), metres. */
    val accuracyM: Float,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val bearingAccuracyDeg: Float? = null,
    val altitudeM: Double? = null,
    val verticalAccuracyM: Float? = null,
    val timeMillis: Long,
    /** Monotonic timestamp (includes deep sleep); used for all durations. */
    val elapsedRealtimeMillis: Long,
    /** True for dead-reckoned positions generated during short GPS loss. */
    val isEstimated: Boolean = false,
    /** True when the platform marks the fix as coming from a mock location provider. */
    val isMock: Boolean = false,
)

enum class HeadingQuality { HIGH, MEDIUM, LOW }

data class Heading(val bearingDeg: Double, val quality: HeadingQuality)

data class MotionState(
    val heading: Heading?,
    val speedMps: Double?,
)

enum class GpsStatus { ACQUIRING, GOOD, WEAK, LOST }

/**
 * Tracking power modes. See docs/05-algorithms.md for the full policy.
 */
enum class TrackingMode {
    /** Alerts off: no location requests, no service. */
    OFF,

    /** Active drive, no camera nearby: passive updates + balanced fallback. */
    PASSIVE,

    /** Camera within ~5 km: balanced-power updates every ~12 s. */
    APPROACH,

    /** Relevant camera within ~1 km: high accuracy every 2-4 s. */
    CLOSE,
}

enum class LocationPriority { HIGH_ACCURACY, BALANCED, LOW_POWER, PASSIVE }

data class LocationRequestSpec(
    val priority: LocationPriority,
    val intervalMs: Long,
    val minUpdateIntervalMs: Long,
    val maxUpdateDelayMs: Long = 0L,
)

/**
 * Complete set of location requests for a tracking mode. [passive] receives
 * fixes produced for other apps (for example a navigation app); [active] is
 * this app's own request.
 */
data class LocationPlan(
    val mode: TrackingMode,
    val passive: LocationRequestSpec?,
    val active: LocationRequestSpec?,
) {
    val requestsHighAccuracy: Boolean get() = active?.priority == LocationPriority.HIGH_ACCURACY

    companion object {
        val NONE = LocationPlan(TrackingMode.OFF, passive = null, active = null)
    }
}

/** Optional map-matching result from a licensed road-data provider. */
data class RoadMatch(
    val roadName: String?,
    val segmentId: String?,
)
