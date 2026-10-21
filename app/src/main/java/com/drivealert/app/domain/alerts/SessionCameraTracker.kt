package com.drivealert.app.domain.alerts

import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.relevance.CameraEvaluation
import com.drivealert.app.domain.relevance.RejectReason

data class TrackerConfig(
    /** Camera counts as passed once it is this far behind (plus half the accuracy)... */
    val passMarginM: Double = 20.0,
    /**
     * ...but only if the driver actually came this close to it. A camera that
     * merely ends up "behind" after turning off the road was not passed and
     * must still warn on a later approach.
     */
    val passProximityM: Double = 150.0,
    /** "Receding" pass detection: closest approach must have been within this distance... */
    val closePassDistanceM: Double = 80.0,
    /** ...and distance must since have grown by this much (plus half the accuracy). */
    val recedeMarginM: Double = 40.0,
    /** Fixes worse than this cannot mark a camera as passed. */
    val maxAccuracyForPassM: Float = 50f,
    /**
     * A passed camera stays silent for the rest of the session unless this
     * much time has elapsed (e.g. returning along the same road much later).
     */
    val rearmAfterPassMs: Long = 30 * 60_000L,
    /** Consecutive "no longer ahead" evaluations before an approach is abandoned. */
    val abandonAfterEvaluations: Int = 3,
    val abandonDistanceGainM: Double = 100.0,
    /** Never re-announce the same camera within this window, even after an abandoned approach. */
    val minRepeatIntervalMs: Long = 3 * 60_000L,
)

data class PendingAlert(
    val camera: Camera,
    val distanceM: Double,
    val thresholdM: Int,
    val isFirstForCamera: Boolean,
)

data class TrackerResult(
    val alerts: List<PendingAlert>,
    val newlyPassed: List<Camera>,
)

/**
 * Per-session state for every camera the driver approaches: which warning
 * bands have already fired, whether the camera has been passed, and
 * suppression of duplicates caused by GPS jitter.
 */
class SessionCameraTracker(
    private val thresholdPolicy: AlertThresholdPolicy = AlertThresholdPolicy(),
    private val config: TrackerConfig = TrackerConfig(),
) {
    private class ApproachState {
        val firedThresholds = HashSet<Int>()
        var wasAhead = false
        var minDistanceM = Double.MAX_VALUE
        var passedAtMs: Long? = null
        var lastAlertAtMs: Long? = null
        var everAlerted = false
        var notAheadStreak = 0

        /** True after an abandoned approach; repeat alerts are rate limited. */
        var isReapproach = false
    }

    private val states = HashMap<String, ApproachState>()

    val passedCount: Int get() = states.values.count { it.passedAtMs != null }

    fun isSuppressed(cameraId: String, nowMillis: Long): Boolean {
        val passedAt = states[cameraId]?.passedAtMs ?: return false
        return nowMillis - passedAt < config.rearmAfterPassMs
    }

    fun hasPassed(cameraId: String): Boolean = states[cameraId]?.passedAtMs != null

    fun process(
        evaluations: List<CameraEvaluation>,
        fix: LocationFix,
        speedMps: Double,
        thresholdsM: List<Int>,
        updateIntervalSeconds: Double,
        nowMillis: Long,
    ): TrackerResult {
        val alerts = ArrayList<PendingAlert>()
        val passed = ArrayList<Camera>()
        val sortedThresholds = thresholdsM.sortedDescending()
        val finalThreshold = sortedThresholds.lastOrNull()

        for (evaluation in evaluations) {
            val id = evaluation.camera.id
            val state = states.getOrPut(id) { ApproachState() }

            val passedAt = state.passedAtMs
            if (passedAt != null) {
                if (nowMillis - passedAt < config.rearmAfterPassMs) continue
                // Re-armed after a long gap: treat as a brand new approach.
                states[id] = ApproachState()
                continue
            }

            if (state.wasAhead && isPassed(state, evaluation, fix)) {
                state.passedAtMs = nowMillis
                passed += evaluation.camera
                continue
            }

            if (evaluation.isAlertable) {
                state.wasAhead = true
                state.notAheadStreak = 0
                if (evaluation.distanceM < state.minDistanceM) state.minDistanceM = evaluation.distanceM

                val crossed = sortedThresholds.filter { threshold ->
                    threshold !in state.firedThresholds &&
                        fix.accuracyM <= thresholdPolicy.maxAccuracyForThresholdM(threshold) &&
                        evaluation.distanceM <= thresholdPolicy.triggerDistanceM(
                            thresholdM = threshold,
                            isFinalThreshold = threshold == finalThreshold,
                            speedMps = speedMps,
                            accuracyM = fix.accuracyM,
                            updateIntervalSeconds = updateIntervalSeconds,
                        )
                }
                if (crossed.isNotEmpty()) {
                    // Several bands crossed at once (e.g. session started 400 m away):
                    // one alert for the closest band, all larger bands consumed.
                    val band = crossed.min()
                    sortedThresholds.filter { it >= band }.forEach { state.firedThresholds += it }
                    val last = state.lastAlertAtMs
                    val repeatTooSoon = state.isReapproach && last != null &&
                        nowMillis - last < config.minRepeatIntervalMs
                    if (!repeatTooSoon) {
                        alerts += PendingAlert(
                            camera = evaluation.camera,
                            distanceM = evaluation.distanceM,
                            thresholdM = band,
                            isFirstForCamera = !state.everAlerted,
                        )
                        state.everAlerted = true
                        state.lastAlertAtMs = nowMillis
                    }
                }
            } else if (state.wasAhead && evaluation.rejectReason in ABANDON_REASONS) {
                if (evaluation.distanceM > state.minDistanceM + config.abandonDistanceGainM) {
                    state.notAheadStreak++
                }
                if (state.notAheadStreak >= config.abandonAfterEvaluations) {
                    // Driver turned away before reaching the camera. Allow a future
                    // approach to warn again, but never within minRepeatIntervalMs.
                    state.wasAhead = false
                    state.firedThresholds.clear()
                    state.minDistanceM = Double.MAX_VALUE
                    state.notAheadStreak = 0
                    state.isReapproach = true
                }
            }
        }
        return TrackerResult(alerts, passed)
    }

    private fun isPassed(state: ApproachState, evaluation: CameraEvaluation, fix: LocationFix): Boolean {
        if (fix.isEstimated || fix.accuracyM > config.maxAccuracyForPassM) return false
        val halfAccuracy = fix.accuracyM * 0.5
        val position = evaluation.position
        val behind = position != null &&
            state.minDistanceM <= config.passProximityM &&
            position.alongTrackM < -(config.passMarginM + halfAccuracy)
        val receding = state.minDistanceM <= config.closePassDistanceM &&
            evaluation.distanceM > state.minDistanceM + config.recedeMarginM + halfAccuracy
        return behind || receding
    }

    /** Clears all state; called when a new driving session starts. */
    fun reset() {
        states.clear()
    }

    private companion object {
        val ABANDON_REASONS = setOf(
            RejectReason.BEHIND,
            RejectReason.NOT_AHEAD,
            RejectReason.CROSSING_ROAD,
            RejectReason.OPPOSITE_DIRECTION,
            RejectReason.DIFFERENT_ROAD,
        )
    }
}
