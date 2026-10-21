package com.drivealert.app.domain.relevance

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.Heading
import com.drivealert.app.domain.model.HeadingQuality
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.model.MotionState
import kotlin.math.max

data class MotionConfig(
    /** GNSS course-over-ground is reliable only above walking speed. */
    val minSpeedForSensorBearingMps: Float = 2.5f,
    val maxBearingAccuracyDeg: Float = 30f,
    /** Minimum displacement before deriving a heading from two fixes. */
    val minDisplacementM: Double = 20.0,
    /** Displacement must also exceed this multiple of the combined accuracy. */
    val displacementAccuracyFactor: Double = 1.5,
    /** A heading older than this is discarded (passive mode can be ~60-90 s apart). */
    val maxHeadingAgeMs: Long = 120_000L,
    val maxSpeedAgeMs: Long = 120_000L,
)

/**
 * Estimates the current direction of travel and speed.
 *
 * Uses the fix's own bearing/speed when the receiver reports them reliably and
 * falls back to the chord between successive fixes (lower quality) otherwise,
 * e.g. for Wi-Fi/cell based balanced-power fixes.
 */
class MotionEstimator(private val config: MotionConfig = MotionConfig()) {
    private var reference: LocationFix? = null
    private var heading: Heading? = null
    private var headingAtMs = 0L
    private var speedMps: Double? = null
    private var speedAtMs = 0L

    fun update(fix: LocationFix): MotionState {
        val now = fix.elapsedRealtimeMillis
        val ref = reference

        fix.speedMps?.let {
            speedMps = it.toDouble()
            speedAtMs = now
        }

        val sensorBearing = fix.bearingDeg
        val sensorSpeed = fix.speedMps
        val sensorUsable = sensorBearing != null && sensorSpeed != null &&
            sensorSpeed >= config.minSpeedForSensorBearingMps &&
            (fix.bearingAccuracyDeg == null || fix.bearingAccuracyDeg <= config.maxBearingAccuracyDeg)

        if (sensorUsable) {
            heading = Heading(GeoMath.normalizeBearing(sensorBearing!!.toDouble()), HeadingQuality.HIGH)
            headingAtMs = now
            reference = fix
        } else if (ref == null) {
            reference = fix
        } else {
            val moved = GeoMath.distanceMeters(ref.latitude, ref.longitude, fix.latitude, fix.longitude)
            val needed = max(
                config.minDisplacementM,
                (ref.accuracyM + fix.accuracyM) * config.displacementAccuracyFactor,
            )
            val dtMs = now - ref.elapsedRealtimeMillis
            if (moved >= needed && dtMs > 0) {
                val bearing = GeoMath.initialBearingDeg(ref.latitude, ref.longitude, fix.latitude, fix.longitude)
                val quality = if (moved >= needed * 2) HeadingQuality.MEDIUM else HeadingQuality.LOW
                heading = Heading(bearing, quality)
                headingAtMs = now
                if (fix.speedMps == null) {
                    speedMps = moved / (dtMs / 1000.0)
                    speedAtMs = now
                }
                reference = fix
            }
        }
        return current(now)
    }

    fun current(nowElapsedMs: Long): MotionState {
        val h = heading?.takeIf { nowElapsedMs - headingAtMs <= config.maxHeadingAgeMs }
        val s = speedMps?.takeIf { nowElapsedMs - speedAtMs <= config.maxSpeedAgeMs }
        return MotionState(h, s)
    }

    fun reset() {
        reference = null
        heading = null
        speedMps = null
        headingAtMs = 0L
        speedAtMs = 0L
    }
}
