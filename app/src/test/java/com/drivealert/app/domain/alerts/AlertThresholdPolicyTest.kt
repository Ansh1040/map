package com.drivealert.app.domain.alerts

import com.drivealert.app.domain.model.AlertDistances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertThresholdPolicyTest {
    private val policy = AlertThresholdPolicy()

    @Test
    fun `stationary with perfect accuracy triggers at the nominal distance`() {
        assertEquals(500.0, policy.triggerDistanceM(500, false, 0.0, 0f, 3.0), 1e-9)
    }

    @Test
    fun `500 m warning at 50 km per hour is pushed earlier by latency and accuracy`() {
        // 500 + 13.89 m/s * (1.5 s + 3 s / 2) + 10 m / 2
        val trigger = policy.triggerDistanceM(500, false, 50 / 3.6, 10f, 3.0)
        assertEquals(500 + (50 / 3.6) * 3.0 + 5.0, trigger, 1e-6)
    }

    @Test
    fun `final warning keeps at least eight seconds of travel at highway speed`() {
        val speed = 100 / 3.6 // 27.8 m/s -> 8 s = 222 m > 200 m
        val trigger = policy.triggerDistanceM(200, true, speed, 5f, 2.0)
        assertEquals(speed * 8.0 + speed * 2.5 + 2.5, trigger, 1e-6)
        assertTrue(trigger / speed >= 8.0)
    }

    @Test
    fun `accuracy contribution is capped`() {
        val capped = policy.triggerDistanceM(1_000, false, 0.0, 400f, 2.0)
        assertEquals(1_025.0, capped, 1e-9)
    }

    @Test
    fun `accuracy requirement scales with the band`() {
        assertEquals(70f, policy.maxAccuracyForThresholdM(200), 0.001f)
        assertEquals(100f, policy.maxAccuracyForThresholdM(1_000), 0.001f)
        assertEquals(35f, policy.maxAccuracyForThresholdM(50), 0.001f)
    }

    @Test
    fun `announced distance is rounded to friendly steps`() {
        assertEquals(500, AlertThresholdPolicy.roundForAnnouncement(523.0))
        assertEquals(550, AlertThresholdPolicy.roundForAnnouncement(540.0))
        assertEquals(1_200, AlertThresholdPolicy.roundForAnnouncement(1_230.0))
        assertEquals(50, AlertThresholdPolicy.roundForAnnouncement(12.0))
    }

    @Test
    fun `threshold lists follow the primary distance`() {
        assertEquals(listOf(1_000, 500, 200), AlertDistances.thresholdsFor(1_000, closerReminders = true))
        assertEquals(listOf(500, 200), AlertDistances.thresholdsFor(500, closerReminders = true))
        assertEquals(listOf(500), AlertDistances.thresholdsFor(500, closerReminders = false))
        assertEquals(listOf(200), AlertDistances.thresholdsFor(180, closerReminders = true))
    }
}
