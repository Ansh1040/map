package com.drivealert.app.domain.alerts

import kotlin.math.max
import kotlin.math.min

data class ThresholdConfig(
    /** Processing + text-to-speech start-up latency. */
    val processingLeadSeconds: Double = 1.5,
    /** The closest warning always gives at least this much time at current speed. */
    val minFinalWarningSeconds: Double = 8.0,
    /** Cap on how much GPS uncertainty can push a warning earlier. */
    val maxAccuracyContributionM: Double = 50.0,
)

/**
 * Converts a nominal warning distance (1000/500/200 m) into the distance at
 * which the alert must actually fire so that it is heard on time.
 *
 * trigger = base + speed * (processing lead + half the update interval) + accuracy / 2
 *
 * where base is the nominal distance, raised for the final warning so it is
 * never less than [ThresholdConfig.minFinalWarningSeconds] of travel. The
 * spoken distance is always the real distance, never the nominal one.
 */
class AlertThresholdPolicy(private val config: ThresholdConfig = ThresholdConfig()) {

    fun triggerDistanceM(
        thresholdM: Int,
        isFinalThreshold: Boolean,
        speedMps: Double,
        accuracyM: Float,
        updateIntervalSeconds: Double,
    ): Double {
        val speed = max(speedMps, 0.0)
        val base = if (isFinalThreshold) {
            max(thresholdM.toDouble(), speed * config.minFinalWarningSeconds)
        } else {
            thresholdM.toDouble()
        }
        val lead = speed * (config.processingLeadSeconds + updateIntervalSeconds * 0.5)
        val accuracyMargin = min(accuracyM.toDouble(), config.maxAccuracyContributionM) * 0.5
        return base + lead + accuracyMargin
    }

    /** Fix accuracy required before an alert for [thresholdM] may fire. */
    fun maxAccuracyForThresholdM(thresholdM: Int): Float =
        (thresholdM * 0.35f).coerceIn(35f, 100f)

    companion object {
        /** Distance rounded for display and voice: 50 m steps below 1 km, 100 m above. */
        fun roundForAnnouncement(distanceM: Double): Int {
            val step = if (distanceM < 1_000) 50 else 100
            val rounded = (Math.round(distanceM / step) * step).toInt()
            return max(rounded, step)
        }
    }
}
