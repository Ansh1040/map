package com.drivealert.app.domain.location

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.LocationFix
import kotlin.math.max

data class StationaryConfig(
    /** Movement inside this radius (plus fix accuracy) does not count as driving. */
    val radiusM: Double = 40.0,
    /** A reported speed above this always counts as moving (crawling traffic). */
    val movingSpeedMps: Float = 2.5f,
    /** Time without movement before the vehicle counts as stationary. */
    val stationaryAfterMs: Long = 120_000L,
    /** Ask "Has your drive ended?" after this long without movement. */
    val promptAfterMs: Long = 10 * 60_000L,
    /** Stop the session automatically after this long without movement. */
    val autoStopAfterMs: Long = 30 * 60_000L,
    /** Without fresh fixes, stationary time is not extrapolated beyond this. */
    val maxExtrapolationMs: Long = 3 * 60_000L,
)

data class StationaryState(
    val isStationary: Boolean,
    val stationaryForMs: Long,
)

/**
 * Detects that the vehicle is not moving, without being fooled by traffic jams:
 * any displacement beyond the radius, or a measured speed above walking pace,
 * restarts the clock. Slow stop-and-go traffic therefore never times out.
 */
class StationaryDetector(private val config: StationaryConfig = StationaryConfig()) {
    private var anchor: LocationFix? = null
    private var stillSinceMs: Long = 0L
    private var lastFixMs: Long = 0L

    val promptAfterMs: Long get() = config.promptAfterMs
    val autoStopAfterMs: Long get() = config.autoStopAfterMs

    fun update(fix: LocationFix): StationaryState {
        val now = fix.elapsedRealtimeMillis
        lastFixMs = now
        val currentAnchor = anchor
        if (currentAnchor == null) {
            anchor = fix
            stillSinceMs = now
            return state(now)
        }
        val moved = GeoMath.distanceMeters(currentAnchor.latitude, currentAnchor.longitude, fix.latitude, fix.longitude)
        val limit = config.radiusM + max(currentAnchor.accuracyM, fix.accuracyM)
        val speedSaysMoving = (fix.speedMps ?: 0f) >= config.movingSpeedMps && fix.accuracyM <= 30f
        if (moved > limit || speedSaysMoving) {
            anchor = fix
            stillSinceMs = now
        }
        return state(now)
    }

    /** Stationary duration at [nowElapsedMs], extrapolated a little past the last fix. */
    fun stationaryForMs(nowElapsedMs: Long): Long {
        if (anchor == null) return 0L
        val effectiveNow = minOf(nowElapsedMs, lastFixMs + config.maxExtrapolationMs)
        return (effectiveNow - stillSinceMs).coerceAtLeast(0L)
    }

    fun state(nowElapsedMs: Long): StationaryState {
        val duration = stationaryForMs(nowElapsedMs)
        return StationaryState(duration >= config.stationaryAfterMs, duration)
    }

    /** The driver confirmed the drive continues: restart the timers. */
    fun restartTimer(nowElapsedMs: Long) {
        stillSinceMs = nowElapsedMs
        lastFixMs = maxOf(lastFixMs, nowElapsedMs)
    }

    fun reset() {
        anchor = null
        stillSinceMs = 0L
        lastFixMs = 0L
    }
}
