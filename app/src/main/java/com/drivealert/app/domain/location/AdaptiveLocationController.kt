package com.drivealert.app.domain.location

import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.model.LocationPlan
import com.drivealert.app.domain.model.TrackingMode
import com.drivealert.app.domain.ports.Clock
import com.drivealert.app.domain.ports.LocationSource

/**
 * Owns the location requests for an alert session and switches between the
 * power modes chosen by [TrackingModePolicy]. Requests are only re-issued when
 * the resulting [LocationPlan] actually changes.
 *
 * Methods are synchronized so that a fix being processed on a worker thread
 * can never re-register a request after [stop] removed every callback.
 */
class AdaptiveLocationController(
    private val source: LocationSource,
    private val policy: TrackingModePolicy,
    private val clock: Clock,
) {
    private val fallback = PassiveFallbackPolicy(policy.config)
    private var appliedPlan: LocationPlan? = null
    private var fastClose = true
    private var stationary = false

    @Volatile
    var mode: TrackingMode = TrackingMode.OFF
        private set

    /** Number of times requests were re-issued; exposed for tests and battery reports. */
    @Volatile
    var planChanges: Int = 0
        private set

    val currentPlan: LocationPlan? @Synchronized get() = appliedPlan

    val expectedIntervalSeconds: Double @Synchronized get() = policy.expectedIntervalSeconds(mode, fastClose)

    @Synchronized
    fun start() {
        mode = TrackingMode.PASSIVE
        fastClose = true
        stationary = false
        planChanges = 0
        appliedPlan = null
        fallback.startSession(clock.elapsedRealtimeMillis())
        applyPlan()
    }

    @Synchronized
    fun onFix(fix: LocationFix) {
        if (mode == TrackingMode.OFF || fix.isEstimated) return
        if (fallback.onFix(fix.elapsedRealtimeMillis) && mode == TrackingMode.PASSIVE) applyPlan()
    }

    /** Called periodically by the session; enables the passive fallback when data goes stale. */
    @Synchronized
    fun onTick() {
        if (mode == TrackingMode.PASSIVE && fallback.onTick(clock.elapsedRealtimeMillis())) applyPlan()
    }

    @Synchronized
    fun update(inputs: TrackingInputs) {
        if (mode == TrackingMode.OFF) return
        val next = policy.decide(mode, inputs)
        if (next != mode) {
            if (next == TrackingMode.PASSIVE) fallback.onEnterPassive(clock.elapsedRealtimeMillis())
            mode = next
        }
        fastClose = policy.useFastCloseInterval(inputs.speedMps, fastClose)
        stationary = inputs.isStationary
        applyPlan()
    }

    @Synchronized
    fun stop() {
        source.stop()
        mode = TrackingMode.OFF
        appliedPlan = null
    }

    private fun applyPlan() {
        val plan = policy.plan(mode, fallback.isActive, fastClose, stationary)
        if (plan != appliedPlan) {
            source.apply(plan)
            appliedPlan = plan
            planChanges++
        }
    }
}
