package com.drivealert.app.domain.location

import com.drivealert.app.domain.model.LocationPlan
import com.drivealert.app.domain.model.LocationPriority
import com.drivealert.app.domain.model.LocationRequestSpec
import com.drivealert.app.domain.model.TrackingMode
import kotlin.math.max

data class TrackingConfig(
    // Mode 3 (APPROACH): any camera within ~5 km.
    val approachEnterM: Double = 5_000.0,
    val approachExitM: Double = 5_500.0,
    val approachIntervalMs: Long = 12_000L,
    val approachMinIntervalMs: Long = 5_000L,

    // Mode 4 (CLOSE): relevant camera within ~1 km (extended at speed, see closeEnterDistanceM).
    val closeMinEnterM: Double = 1_000.0,
    val closeExitExtraM: Double = 400.0,
    val closeMarginM: Double = 150.0,
    val closeFastIntervalMs: Long = 2_000L,
    val closeSlowIntervalMs: Long = 4_000L,
    val closeMinIntervalMs: Long = 1_000L,
    val closeFastEnterSpeedMps: Double = 16.7, // 60 km/h
    val closeFastExitSpeedMps: Double = 13.9, // 50 km/h

    // Mode 2 (PASSIVE): piggy-back on fixes produced for other apps.
    val passiveIntervalMs: Long = 10_000L,
    val passiveMinIntervalMs: Long = 5_000L,
    val fallbackIntervalMs: Long = 45_000L,
    val fallbackMinIntervalMs: Long = 15_000L,
    val stationaryFallbackIntervalMs: Long = 90_000L,
    /** Passive data older than this enables the balanced fallback request. */
    val passiveStaleAfterMs: Long = 60_000L,
    /** This many fixes inside the window means another app is producing locations. */
    val otherAppFixesToDisableFallback: Int = 3,
    val otherAppWindowMs: Long = 30_000L,

    /** Stationary this long (e.g. long red light, jam) drops one power level. */
    val stationaryDowngradeAfterMs: Long = 120_000L,
)

data class TrackingInputs(
    /** Nearest usable camera in any direction (drives Mode 3). */
    val nearestCameraM: Double?,
    /** Nearest camera that is roughly ahead and not yet passed (drives Mode 4). */
    val nearestApproachCandidateM: Double?,
    val speedMps: Double,
    val isStationary: Boolean,
    val stationaryForMs: Long,
    val primaryAlertDistanceM: Int,
)

/**
 * Pure decision logic for adaptive location tracking. Hysteresis on every
 * boundary prevents rapid request churn, which itself costs power.
 */
class TrackingModePolicy(val config: TrackingConfig = TrackingConfig()) {

    /**
     * Distance at which precise tracking must begin so that the first warning
     * is not missed between two balanced-power fixes.
     */
    fun closeEnterDistanceM(primaryAlertDistanceM: Int, speedMps: Double): Double =
        max(
            config.closeMinEnterM,
            primaryAlertDistanceM + max(speedMps, 0.0) * (config.approachIntervalMs / 1000.0) * 1.5 + config.closeMarginM,
        )

    fun decide(current: TrackingMode, inputs: TrackingInputs): TrackingMode {
        val closeEnter = closeEnterDistanceM(inputs.primaryAlertDistanceM, inputs.speedMps)
        val candidate = inputs.nearestApproachCandidateM
        val any = inputs.nearestCameraM
        val inCloseOrApproach = current == TrackingMode.CLOSE || current == TrackingMode.APPROACH

        var mode = when {
            candidate != null && candidate <= closeEnter -> TrackingMode.CLOSE
            current == TrackingMode.CLOSE && candidate != null && candidate <= closeEnter + config.closeExitExtraM ->
                TrackingMode.CLOSE
            any != null && any <= config.approachEnterM -> TrackingMode.APPROACH
            inCloseOrApproach && any != null && any <= config.approachExitM -> TrackingMode.APPROACH
            else -> TrackingMode.PASSIVE
        }

        if (inputs.isStationary && inputs.stationaryForMs >= config.stationaryDowngradeAfterMs) {
            mode = when (mode) {
                TrackingMode.CLOSE -> TrackingMode.APPROACH
                TrackingMode.APPROACH -> TrackingMode.PASSIVE
                else -> mode
            }
        }
        return mode
    }

    /** Speed hysteresis for the CLOSE-mode interval (2 s fast / 4 s slow). */
    fun useFastCloseInterval(speedMps: Double, currentlyFast: Boolean): Boolean = when {
        speedMps >= config.closeFastEnterSpeedMps -> true
        speedMps < config.closeFastExitSpeedMps -> false
        else -> currentlyFast
    }

    fun plan(mode: TrackingMode, fallbackActive: Boolean, fastClose: Boolean, stationary: Boolean): LocationPlan =
        when (mode) {
            TrackingMode.OFF -> LocationPlan.NONE
            TrackingMode.PASSIVE -> LocationPlan(
                mode = mode,
                passive = LocationRequestSpec(
                    priority = LocationPriority.PASSIVE,
                    intervalMs = config.passiveIntervalMs,
                    minUpdateIntervalMs = config.passiveMinIntervalMs,
                ),
                active = if (fallbackActive) {
                    LocationRequestSpec(
                        priority = LocationPriority.BALANCED,
                        intervalMs = if (stationary) config.stationaryFallbackIntervalMs else config.fallbackIntervalMs,
                        minUpdateIntervalMs = config.fallbackMinIntervalMs,
                    )
                } else {
                    null
                },
            )
            TrackingMode.APPROACH -> LocationPlan(
                mode = mode,
                passive = null,
                active = LocationRequestSpec(
                    priority = LocationPriority.BALANCED,
                    intervalMs = config.approachIntervalMs,
                    minUpdateIntervalMs = config.approachMinIntervalMs,
                ),
            )
            TrackingMode.CLOSE -> LocationPlan(
                mode = mode,
                passive = null,
                active = LocationRequestSpec(
                    priority = LocationPriority.HIGH_ACCURACY,
                    intervalMs = if (fastClose) config.closeFastIntervalMs else config.closeSlowIntervalMs,
                    minUpdateIntervalMs = config.closeMinIntervalMs,
                ),
            )
        }

    /** Expected seconds between fixes in [mode]; used for alert lead-time. */
    fun expectedIntervalSeconds(mode: TrackingMode, fastClose: Boolean): Double = when (mode) {
        TrackingMode.CLOSE -> (if (fastClose) config.closeFastIntervalMs else config.closeSlowIntervalMs) / 1000.0
        TrackingMode.APPROACH -> config.approachIntervalMs / 1000.0
        TrackingMode.PASSIVE, TrackingMode.OFF -> config.fallbackIntervalMs / 1000.0
    }
}

/**
 * Decides when PASSIVE mode needs its balanced-power fallback request.
 *
 * Passive fixes only arrive while another app (e.g. a navigation app) is
 * requesting location. If none arrive for [TrackingConfig.passiveStaleAfterMs]
 * a 45 s balanced request is added. While the fallback runs, fixes arriving
 * faster than it could produce them prove another app is active again, so the
 * fallback is removed.
 */
class PassiveFallbackPolicy(private val config: TrackingConfig) {
    var isActive: Boolean = false
        private set

    private val recentFixes = ArrayDeque<Long>()
    private var lastFixElapsedMs: Long = 0L

    /** Session start: no passive data is known yet, so begin with the fallback on. */
    fun startSession(nowElapsedMs: Long) {
        recentFixes.clear()
        lastFixElapsedMs = nowElapsedMs
        isActive = true
    }

    /** Entering PASSIVE from a higher mode: give passive data a chance first. */
    fun onEnterPassive(nowElapsedMs: Long) {
        recentFixes.clear()
        lastFixElapsedMs = nowElapsedMs
        isActive = false
    }

    /** @return true when [isActive] changed. */
    fun onFix(elapsedMs: Long): Boolean {
        lastFixElapsedMs = elapsedMs
        recentFixes.addLast(elapsedMs)
        while (recentFixes.isNotEmpty() && elapsedMs - recentFixes.first() > config.otherAppWindowMs) {
            recentFixes.removeFirst()
        }
        if (isActive && recentFixes.size >= config.otherAppFixesToDisableFallback) {
            isActive = false
            recentFixes.clear()
            return true
        }
        return false
    }

    /** @return true when [isActive] changed. */
    fun onTick(nowElapsedMs: Long): Boolean {
        if (!isActive && nowElapsedMs - lastFixElapsedMs >= config.passiveStaleAfterMs) {
            isActive = true
            recentFixes.clear()
            return true
        }
        return false
    }
}
