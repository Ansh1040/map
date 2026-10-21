package com.drivealert.app.domain.location

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.LocationFix

data class DeadReckoningConfig(
    /** Start estimating after this long without a fix in CLOSE mode. */
    val gapBeforeEstimateMs: Long = 6_000L,
    /** Stop estimating after this long; the position is too uncertain afterwards. */
    val maxEstimateMs: Long = 45_000L,
    val minSpeedMps: Double = 3.0,
    /** Accuracy grows by this fraction of the distance travelled blind. */
    val accuracyGrowthPerMeter: Double = 0.15,
)

/**
 * Short-term position prediction during GPS loss (tunnels, underpasses, dense
 * flyovers). Estimated fixes may trigger warnings, but never mark a camera as
 * passed, update the heading or reset the stationary timer.
 */
object DeadReckoning {
    fun predict(last: LocationFix, headingDeg: Double, speedMps: Double, elapsedMs: Long, config: DeadReckoningConfig): LocationFix {
        val distance = speedMps * elapsedMs / 1000.0
        val point = GeoMath.destination(last.latitude, last.longitude, headingDeg, distance)
        return last.copy(
            latitude = point.latitude,
            longitude = point.longitude,
            accuracyM = (last.accuracyM + distance * config.accuracyGrowthPerMeter).toFloat(),
            timeMillis = last.timeMillis + elapsedMs,
            elapsedRealtimeMillis = last.elapsedRealtimeMillis + elapsedMs,
            isEstimated = true,
        )
    }
}
