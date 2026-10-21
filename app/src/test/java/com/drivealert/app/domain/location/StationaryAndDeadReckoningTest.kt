package com.drivealert.app.domain.location

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StationaryAndDeadReckoningTest {

    @Test
    fun `parked vehicle becomes stationary after two minutes`() {
        val detector = StationaryDetector()
        val here = Fixtures.point(0.0)
        var state = detector.update(Fixtures.fix(here, 0L, speedMps = 0f))
        assertFalse(state.isStationary)
        for (t in 10_000L..130_000L step 10_000L) {
            // GPS noise of a few metres while parked.
            val jitter = Fixtures.point((t % 3).toDouble() * 3.0)
            state = detector.update(Fixtures.fix(jitter, t, speedMps = 0.2f))
        }
        assertTrue(state.isStationary)
        assertTrue(state.stationaryForMs >= 120_000L)
    }

    @Test
    fun `slow traffic jam never counts as stationary`() {
        val detector = StationaryDetector()
        var position = 0.0
        var stationarySeen = false
        for (t in 0L..20 * 60_000L step 20_000L) {
            position += 20.0 // 1 m/s crawl, reported speed low
            val state = detector.update(Fixtures.fix(Fixtures.point(position), t, speedMps = 1.0f))
            stationarySeen = stationarySeen || state.stationaryForMs >= detector.promptAfterMs
        }
        assertFalse(stationarySeen)
    }

    @Test
    fun `moving again restarts the timer`() {
        val detector = StationaryDetector()
        val here = Fixtures.point(0.0)
        detector.update(Fixtures.fix(here, 0L, speedMps = 0f))
        detector.update(Fixtures.fix(here, 200_000L, speedMps = 0f))
        assertTrue(detector.state(200_000L).isStationary)
        val state = detector.update(Fixtures.fix(here, 210_000L, speedMps = 4f))
        assertFalse(state.isStationary)
    }

    @Test
    fun `stationary time is not extrapolated far beyond the last fix`() {
        val detector = StationaryDetector()
        val here = Fixtures.point(0.0)
        detector.update(Fixtures.fix(here, 0L, speedMps = 0f))
        detector.update(Fixtures.fix(here, 60_000L, speedMps = 0f))
        // No fixes for an hour: duration is capped at last fix + 3 minutes.
        assertEquals(240_000L, detector.stationaryForMs(3_600_000L))
    }

    @Test
    fun `prompt and auto-stop thresholds are ordered and clearly defined`() {
        val detector = StationaryDetector()
        assertEquals(10 * 60_000L, detector.promptAfterMs)
        assertEquals(30 * 60_000L, detector.autoStopAfterMs)
    }

    @Test
    fun `dead reckoning predicts along the heading and grows uncertainty`() {
        val last = Fixtures.fix(Fixtures.point(0.0), 0L, accuracyM = 5f)
        val config = DeadReckoningConfig()
        val predicted = DeadReckoning.predict(last, headingDeg = 0.0, speedMps = 20.0, elapsedMs = 10_000L, config = config)
        val travelled = GeoMath.distanceMeters(last.latitude, last.longitude, predicted.latitude, predicted.longitude)
        assertEquals(200.0, travelled, 0.5)
        assertEquals(5f + 200f * 0.15f, predicted.accuracyM, 0.01f)
        assertTrue(predicted.isEstimated)
        assertEquals(10_000L, predicted.elapsedRealtimeMillis)
    }
}
