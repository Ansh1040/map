package com.drivealert.app.domain.location

import com.drivealert.app.domain.model.LocationPriority
import com.drivealert.app.domain.model.TrackingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingModePolicyTest {
    private val policy = TrackingModePolicy()

    private fun inputs(
        any: Double? = null,
        candidate: Double? = null,
        speed: Double = 15.0,
        stationary: Boolean = false,
        stationaryFor: Long = 0L,
        primary: Int = 500,
    ) = TrackingInputs(any, candidate, speed, stationary, stationaryFor, primary)

    @Test
    fun `no camera nearby stays in passive mode`() {
        assertEquals(TrackingMode.PASSIVE, policy.decide(TrackingMode.PASSIVE, inputs()))
        assertEquals(TrackingMode.PASSIVE, policy.decide(TrackingMode.PASSIVE, inputs(any = 7_000.0)))
    }

    @Test
    fun `camera within 5 km switches to approach mode`() {
        assertEquals(TrackingMode.APPROACH, policy.decide(TrackingMode.PASSIVE, inputs(any = 4_500.0)))
    }

    @Test
    fun `relevant camera within about 1 km switches to close mode`() {
        assertEquals(TrackingMode.CLOSE, policy.decide(TrackingMode.APPROACH, inputs(any = 900.0, candidate = 900.0, speed = 0.0)))
    }

    @Test
    fun `camera that is not a candidate never triggers high accuracy`() {
        // e.g. opposite carriageway 300 m away: balanced only.
        assertEquals(TrackingMode.APPROACH, policy.decide(TrackingMode.APPROACH, inputs(any = 300.0, candidate = null)))
    }

    @Test
    fun `close-mode entry distance grows with speed`() {
        assertEquals(1_000.0, policy.closeEnterDistanceM(500, 0.0), 1e-9)
        val highway = 100 / 3.6
        assertEquals(1_000 + highway * 12 * 1.5 + 150, policy.closeEnterDistanceM(1_000, highway), 1e-6)
    }

    @Test
    fun `hysteresis keeps modes stable near boundaries`() {
        // Close mode is kept slightly beyond the entry distance...
        assertEquals(TrackingMode.CLOSE, policy.decide(TrackingMode.CLOSE, inputs(any = 1_300.0, candidate = 1_300.0, speed = 0.0)))
        // ...but not from approach mode.
        assertEquals(TrackingMode.APPROACH, policy.decide(TrackingMode.APPROACH, inputs(any = 1_300.0, candidate = 1_300.0, speed = 0.0)))
        assertEquals(TrackingMode.APPROACH, policy.decide(TrackingMode.APPROACH, inputs(any = 5_300.0)))
        assertEquals(TrackingMode.PASSIVE, policy.decide(TrackingMode.PASSIVE, inputs(any = 5_300.0)))
        assertEquals(TrackingMode.PASSIVE, policy.decide(TrackingMode.APPROACH, inputs(any = 5_600.0)))
    }

    @Test
    fun `long stationary period drops one power level`() {
        val parked = inputs(any = 300.0, candidate = 300.0, stationary = true, stationaryFor = 180_000L, speed = 0.0)
        assertEquals(TrackingMode.APPROACH, policy.decide(TrackingMode.CLOSE, parked))
        val brief = parked.copy(stationaryForMs = 60_000L)
        assertEquals(TrackingMode.CLOSE, policy.decide(TrackingMode.CLOSE, brief))
    }

    @Test
    fun `plans use the documented priorities and intervals`() {
        val passive = policy.plan(TrackingMode.PASSIVE, fallbackActive = false, fastClose = true, stationary = false)
        assertEquals(LocationPriority.PASSIVE, passive.passive?.priority)
        assertNull(passive.active)

        val fallback = policy.plan(TrackingMode.PASSIVE, fallbackActive = true, fastClose = true, stationary = false)
        assertEquals(LocationPriority.BALANCED, fallback.active?.priority)
        assertEquals(45_000L, fallback.active?.intervalMs)
        assertEquals(90_000L, policy.plan(TrackingMode.PASSIVE, true, true, stationary = true).active?.intervalMs)

        val approach = policy.plan(TrackingMode.APPROACH, false, true, false)
        assertEquals(LocationPriority.BALANCED, approach.active?.priority)
        assertEquals(12_000L, approach.active?.intervalMs)

        val closeFast = policy.plan(TrackingMode.CLOSE, false, fastClose = true, stationary = false)
        assertEquals(LocationPriority.HIGH_ACCURACY, closeFast.active?.priority)
        assertEquals(2_000L, closeFast.active?.intervalMs)
        assertEquals(4_000L, policy.plan(TrackingMode.CLOSE, false, fastClose = false, stationary = false).active?.intervalMs)

        assertFalse(passive.requestsHighAccuracy || fallback.requestsHighAccuracy || approach.requestsHighAccuracy)
        assertTrue(closeFast.requestsHighAccuracy)
        assertNull(policy.plan(TrackingMode.OFF, true, true, false).active)
    }

    @Test
    fun `close interval speed hysteresis`() {
        assertTrue(policy.useFastCloseInterval(20.0, currentlyFast = false))
        assertFalse(policy.useFastCloseInterval(10.0, currentlyFast = true))
        assertTrue(policy.useFastCloseInterval(15.0, currentlyFast = true))
        assertFalse(policy.useFastCloseInterval(15.0, currentlyFast = false))
    }

    @Test
    fun `passive fallback turns on when passive data is stale and off when another app supplies fixes`() {
        val fallback = PassiveFallbackPolicy(TrackingConfig())
        fallback.onEnterPassive(0L)
        assertFalse(fallback.isActive)
        assertFalse(fallback.onTick(59_000L))
        assertTrue(fallback.onTick(60_000L))
        assertTrue(fallback.isActive)

        // Our own 45 s fallback alone never disables itself...
        assertFalse(fallback.onFix(105_000L))
        assertFalse(fallback.onFix(150_000L))
        assertTrue(fallback.isActive)
        // ...but a burst of fixes from a navigation app does.
        fallback.onFix(155_000L)
        assertTrue(fallback.onFix(160_000L))
        assertFalse(fallback.isActive)
    }

    @Test
    fun `session starts with the fallback active`() {
        val fallback = PassiveFallbackPolicy(TrackingConfig())
        fallback.startSession(0L)
        assertTrue(fallback.isActive)
        assertNotNull(policy.plan(TrackingMode.PASSIVE, fallback.isActive, true, false).active)
    }
}
