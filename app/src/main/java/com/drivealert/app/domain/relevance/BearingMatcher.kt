package com.drivealert.app.domain.relevance

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.geo.RelativePosition
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.Directionality
import com.drivealert.app.domain.model.HeadingQuality
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class DirectionMatch {
    /** Travel direction matches the monitored direction. */
    MATCH,

    /** Travelling against the monitored direction, e.g. the opposite carriageway. */
    OPPOSITE,

    /** Travelling across the monitored direction, e.g. a crossing road at a junction. */
    CROSSING,

    /** Camera direction not known. */
    UNKNOWN,
}

enum class AheadState { AHEAD, BESIDE, BEHIND }

data class BearingConfig(
    val directionToleranceDeg: Double = 45.0,
    /** Extra tolerance when the heading was derived from coarse fixes. */
    val lowQualityExtraToleranceDeg: Double = 15.0,
    /** Lenient cone used only for power-mode decisions. */
    val approachConeDeg: Double = 75.0,
    /** Small negative along-track allowance before a camera counts as behind. */
    val behindMarginM: Double = 15.0,
)

/**
 * Compares the direction of travel with a camera's monitored direction and
 * decides whether a camera is ahead of, beside or behind the driver.
 */
class BearingMatcher(private val config: BearingConfig = BearingConfig()) {

    fun matchDirection(headingDeg: Double, quality: HeadingQuality, camera: Camera): DirectionMatch {
        val cameraBearing = camera.bearingDeg ?: return DirectionMatch.UNKNOWN
        val tolerance = toleranceFor(quality)
        val diff = GeoMath.angularDifferenceDeg(headingDeg, cameraBearing)
        return when (camera.directionality) {
            Directionality.UNKNOWN -> DirectionMatch.UNKNOWN
            Directionality.ONE_DIRECTION -> when {
                diff <= tolerance -> DirectionMatch.MATCH
                diff >= 180.0 - tolerance -> DirectionMatch.OPPOSITE
                else -> DirectionMatch.CROSSING
            }
            Directionality.BOTH_DIRECTIONS -> {
                val axisDiff = min(diff, 180.0 - diff)
                if (axisDiff <= tolerance) DirectionMatch.MATCH else DirectionMatch.CROSSING
            }
        }
    }

    fun toleranceFor(quality: HeadingQuality): Double = when (quality) {
        HeadingQuality.HIGH -> config.directionToleranceDeg
        HeadingQuality.MEDIUM -> config.directionToleranceDeg + config.lowQualityExtraToleranceDeg / 2
        HeadingQuality.LOW -> config.directionToleranceDeg + config.lowQualityExtraToleranceDeg
    }

    /**
     * Strict ahead test used for alerts. The permitted cone narrows with
     * distance so a camera far off to the side is not treated as "ahead",
     * while still tolerating gentle road curvature.
     */
    fun classifyAhead(position: RelativePosition, accuracyM: Float, lateralToleranceM: Double): AheadState {
        val behindMargin = max(config.behindMarginM, accuracyM * 0.5)
        if (position.alongTrackM < -behindMargin) return AheadState.BEHIND
        if (position.distanceM <= accuracyM + 10.0) return AheadState.BESIDE
        val withinCone = abs(position.relativeAngleDeg) <= strictConeDeg(position.distanceM)
        val withinLane = position.alongTrackM > 0 && abs(position.crossTrackM) <= lateralToleranceM
        return when {
            position.alongTrackM > 0 && (withinCone || withinLane) -> AheadState.AHEAD
            position.alongTrackM > 0 -> AheadState.BESIDE
            else -> AheadState.BEHIND
        }
    }

    /** Lenient ahead test: is it worth spending power on precise tracking? */
    fun isRoughlyAhead(position: RelativePosition): Boolean =
        position.alongTrackM > 0 && abs(position.relativeAngleDeg) <= config.approachConeDeg

    fun strictConeDeg(distanceM: Double): Double = when {
        distanceM >= 600.0 -> 35.0
        distanceM >= 200.0 -> 45.0
        distanceM >= 60.0 -> 60.0
        else -> 75.0
    }
}
