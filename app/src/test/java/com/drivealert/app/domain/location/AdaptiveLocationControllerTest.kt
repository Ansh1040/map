package com.drivealert.app.domain.location

import com.drivealert.app.domain.model.LocationPriority
import com.drivealert.app.domain.model.TrackingMode
import com.drivealert.app.testutil.FakeClock
import com.drivealert.app.testutil.FakeLocationSource
import com.drivealert.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLocationControllerTest {
    private var now = 0L
    private val source = FakeLocationSource()
    private val controller = AdaptiveLocationController(source, TrackingModePolicy(), FakeClock { now })

    private fun inputs(any: Double? = null, candidate: Double? = null, speed: Double = 20.0) =
        TrackingInputs(any, candidate, speed, isStationary = false, stationaryForMs = 0L, primaryAlertDistanceM = 500)

    @Test
    fun `alerts off means no location requests`() {
        assertEquals(TrackingMode.OFF, controller.mode)
        controller.update(inputs(candidate = 100.0))
        assertTrue(source.appliedPlans.isEmpty())
    }

    @Test
    fun `mode progression passive - approach - close - approach`() {
        controller.start()
        assertEquals(TrackingMode.PASSIVE, controller.mode)
        assertEquals(LocationPriority.BALANCED, source.currentPlan?.active?.priority) // start-up fallback

        controller.update(inputs(any = 4_000.0, candidate = 4_000.0))
        assertEquals(TrackingMode.APPROACH, controller.mode)
        assertEquals(LocationPriority.BALANCED, source.currentPlan?.active?.priority)

        controller.update(inputs(any = 900.0, candidate = 900.0))
        assertEquals(TrackingMode.CLOSE, controller.mode)
        assertEquals(LocationPriority.HIGH_ACCURACY, source.currentPlan?.active?.priority)

        // Camera passed: no candidate any more, another camera 3 km away.
        controller.update(inputs(any = 3_000.0, candidate = null))
        assertEquals(TrackingMode.APPROACH, controller.mode)
        assertEquals(LocationPriority.BALANCED, source.currentPlan?.active?.priority)
    }

    @Test
    fun `requests are only re-issued when the plan changes`() {
        controller.start()
        repeat(10) { controller.update(inputs(any = 4_000.0)) }
        assertEquals(2, controller.planChanges)
    }

    @Test
    fun `passive mode drops its fallback when another app provides fixes`() {
        controller.start()
        controller.update(inputs())
        assertEquals(LocationPriority.BALANCED, source.currentPlan?.active?.priority)
        listOf(1_000L, 6_000L, 11_000L).forEach { t ->
            now = t
            controller.onFix(Fixtures.fix(Fixtures.point(0.0), elapsedMs = t))
        }
        assertNull(source.currentPlan?.active)
        assertEquals(LocationPriority.PASSIVE, source.currentPlan?.passive?.priority)

        now = 75_000L
        controller.onTick()
        assertEquals(LocationPriority.BALANCED, source.currentPlan?.active?.priority)
    }

    @Test
    fun `stop removes every request and later updates cannot re-register`() {
        controller.start()
        controller.update(inputs(any = 900.0, candidate = 900.0))
        controller.stop()
        assertEquals(1, source.stopCount)
        assertEquals(TrackingMode.OFF, controller.mode)
        val before = source.appliedPlans.size
        controller.update(inputs(any = 500.0, candidate = 500.0))
        controller.onTick()
        assertEquals(before, source.appliedPlans.size)
        assertNull(source.currentPlan)
    }
}
