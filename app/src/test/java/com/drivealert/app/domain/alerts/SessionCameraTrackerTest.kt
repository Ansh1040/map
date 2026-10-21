package com.drivealert.app.domain.alerts

import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.Heading
import com.drivealert.app.domain.model.HeadingQuality
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.relevance.CameraRelevanceEngine
import com.drivealert.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCameraTrackerTest {
    private val engine = CameraRelevanceEngine()
    private val north = Heading(0.0, HeadingQuality.HIGH)
    private val thresholds = listOf(1_000, 500, 200)
    private val camera: Camera = Fixtures.camera("cam", Fixtures.point(2_000.0))

    private fun step(tracker: SessionCameraTracker, fix: LocationFix, speed: Double = 15.0, cameras: List<Camera> = listOf(camera)): TrackerResult {
        val evaluations = engine.evaluate(fix, north, cameras, nowMillis = 0L, includeUnverified = false)
        return tracker.process(evaluations, fix, speed, thresholds, updateIntervalSeconds = 2.0, nowMillis = fix.elapsedRealtimeMillis)
    }

    /** Drives north from [fromM] to [toM] at 15 m/s with a fix every 2 s. */
    private fun drive(tracker: SessionCameraTracker, fromM: Double, toM: Double, startMs: Long = 0L, accuracy: Float = 5f): Pair<List<PendingAlert>, List<Camera>> {
        val alerts = ArrayList<PendingAlert>()
        val passed = ArrayList<Camera>()
        var position = fromM
        var t = startMs
        while (position <= toM) {
            val result = step(tracker, Fixtures.fix(Fixtures.point(position), elapsedMs = t, accuracyM = accuracy))
            alerts += result.alerts
            passed += result.newlyPassed
            position += 30.0
            t += 2_000L
        }
        return alerts to passed
    }

    @Test
    fun `each warning band fires exactly once and the camera is then passed`() {
        val tracker = SessionCameraTracker()
        val (alerts, passed) = drive(tracker, 0.0, 2_300.0)
        assertEquals(listOf(1_000, 500, 200), alerts.map { it.thresholdM })
        assertTrue(alerts.first().isFirstForCamera)
        assertFalse(alerts[1].isFirstForCamera)
        assertEquals(listOf("cam"), passed.map { it.id })
        assertTrue(tracker.hasPassed("cam"))
    }

    @Test
    fun `alerts fire at or before the nominal distance`() {
        val tracker = SessionCameraTracker()
        val (alerts, _) = drive(tracker, 0.0, 2_300.0)
        alerts.forEach { assertTrue("band ${it.thresholdM} fired at ${it.distanceM}", it.distanceM >= it.thresholdM - 30) }
    }

    @Test
    fun `starting inside a band fires one alert, not a burst`() {
        val tracker = SessionCameraTracker()
        val (alerts, _) = drive(tracker, 1_600.0, 1_700.0) // 400..300 m from the camera
        assertEquals(1, alerts.size)
        assertEquals(500, alerts.single().thresholdM)
    }

    @Test
    fun `gps jitter around a threshold does not repeat the alert`() {
        val tracker = SessionCameraTracker()
        var t = 0L
        val alerts = ArrayList<PendingAlert>()
        repeat(20) { i ->
            val wobble = if (i % 2 == 0) 0.0 else -40.0
            alerts += step(tracker, Fixtures.fix(Fixtures.point(1_480.0 + wobble), elapsedMs = t)).alerts
            t += 2_000L
        }
        assertEquals(1, alerts.size)
    }

    @Test
    fun `passed camera stays silent for the rest of the session`() {
        val tracker = SessionCameraTracker()
        drive(tracker, 0.0, 2_300.0)
        // Drive the same stretch again shortly afterwards.
        val (again, _) = drive(tracker, 0.0, 2_300.0, startMs = 300_000L)
        assertTrue(again.isEmpty())
    }

    @Test
    fun `reset prepares a fresh session`() {
        val tracker = SessionCameraTracker()
        drive(tracker, 0.0, 2_300.0)
        tracker.reset()
        val (alerts, _) = drive(tracker, 0.0, 2_300.0, startMs = 600_000L)
        assertEquals(3, alerts.size)
    }

    @Test
    fun `camera re-arms after the configured gap`() {
        val tracker = SessionCameraTracker(config = TrackerConfig(rearmAfterPassMs = 60_000L))
        drive(tracker, 0.0, 2_300.0)
        val (alerts, _) = drive(tracker, 0.0, 2_300.0, startMs = 10 * 60_000L)
        assertEquals(3, alerts.size)
    }

    @Test
    fun `inaccurate fixes cannot mark a camera as passed`() {
        val tracker = SessionCameraTracker()
        drive(tracker, 0.0, 1_960.0)
        val result = step(tracker, Fixtures.fix(Fixtures.point(2_200.0), elapsedMs = 200_000L, accuracyM = 80f))
        assertTrue(result.newlyPassed.isEmpty())
        assertFalse(tracker.hasPassed("cam"))
    }

    @Test
    fun `turning away abandons the approach without marking it passed`() {
        val tracker = SessionCameraTracker()
        drive(tracker, 0.0, 1_100.0) // 1000 m band fired
        // Turn east and drive away: camera is now off to the side and receding.
        var t = 100_000L
        repeat(6) { i ->
            val p = Fixtures.point(1_100.0)
            val east = com.drivealert.app.domain.geo.GeoMath.destination(p.latitude, p.longitude, 90.0, 200.0 * (i + 1))
            val fix = Fixtures.fix(east, elapsedMs = t, bearingDeg = 90f)
            tracker.process(engine.evaluate(fix, Heading(90.0, HeadingQuality.HIGH), listOf(camera), 0L, false), fix, 15.0, thresholds, 2.0, t)
            t += 2_000L
        }
        assertFalse(tracker.hasPassed("cam"))
        assertFalse(tracker.isSuppressed("cam", t))

        // Coming back to the road later (after the repeat window) warns again.
        val (later, _) = drive(tracker, 0.0, 1_100.0, startMs = t + 10 * 60_000L)
        assertEquals(listOf(1_000), later.map { it.thresholdM })
    }
}
