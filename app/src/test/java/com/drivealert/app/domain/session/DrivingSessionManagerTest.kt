package com.drivealert.app.domain.session

import com.drivealert.app.domain.alerts.AlertThresholdPolicy
import com.drivealert.app.domain.cameras.NearbyCameraIndex
import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.location.AdaptiveLocationController
import com.drivealert.app.domain.location.TrackingModePolicy
import com.drivealert.app.domain.model.AlertSettings
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.LocationPriority
import com.drivealert.app.domain.model.SessionEvent
import com.drivealert.app.domain.model.SessionState
import com.drivealert.app.domain.model.StopReason
import com.drivealert.app.domain.model.TrackingMode
import com.drivealert.app.domain.relevance.CameraRelevanceEngine
import com.drivealert.app.testutil.FakeClock
import com.drivealert.app.testutil.FakeLocationSource
import com.drivealert.app.testutil.Fixtures
import com.drivealert.app.testutil.InMemoryCameraLookup
import com.drivealert.app.testutil.RecordingHistory
import com.drivealert.app.testutil.RecordingPresenter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end simulation of a driving session in plain JVM time: a car drives
 * north at 20 m/s past synthetic cameras and we check what the driver hears
 * and how much GPS power is requested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrivingSessionManagerTest {

    private val ahead = Fixtures.camera("ahead", Fixtures.point(4_000.0))
    private val opposite = Fixtures.camera("opposite", Fixtures.point(2_500.0, rightM = 25.0), bearingDeg = 180.0)
    private val behind = Fixtures.camera("behind", Fixtures.point(-500.0))
    private val crossing = Fixtures.camera("crossing", Fixtures.point(6_000.0, rightM = 30.0), bearingDeg = 90.0)

    private class Harness(
        scope: TestScope,
        cameras: List<Camera>,
        settings: AlertSettings = AlertSettings(primaryAlertDistanceM = 1_000),
    ) {
        val source = FakeLocationSource()
        val presenter = RecordingPresenter()
        val history = RecordingHistory()
        val clock = FakeClock { scope.testScheduler.currentTime }
        val controller = AdaptiveLocationController(source, TrackingModePolicy(), clock)
        val manager = DrivingSessionManager(
            locationSource = source,
            locationController = controller,
            cameraIndex = NearbyCameraIndex(InMemoryCameraLookup(cameras)),
            relevanceEngine = CameraRelevanceEngine(),
            thresholdPolicy = AlertThresholdPolicy(),
            alertPresenter = presenter,
            historyRecorder = history,
            settings = MutableStateFlow(settings),
            clock = clock,
            idFactory = { "session-1" },
        )
        /** (distance to the "ahead" camera, plan) every time requests change. */
        val planLog = ArrayList<Pair<Double, LocationPriority?>>()
    }

    private fun TestScope.driveNorth(h: Harness, fromM: Double, toM: Double, speedMps: Double = 20.0, stepMs: Long = 2_000L) {
        var position = fromM
        while (position <= toM) {
            val now = testScheduler.currentTime
            val plansBefore = h.source.appliedPlans.size
            h.source.emit(Fixtures.fix(Fixtures.point(position), elapsedMs = now, speedMps = speedMps.toFloat()))
            runCurrent()
            if (h.source.appliedPlans.size != plansBefore) {
                h.planLog += (4_000.0 - position) to h.source.appliedPlans.last().active?.priority
            }
            advanceTimeBy(stepMs)
            position += speedMps * stepMs / 1000.0
        }
    }

    @Test
    fun `warns only about the relevant camera, once per band`() = runTest {
        val h = Harness(this, listOf(ahead, opposite, behind, crossing))
        assertTrue(h.manager.start(backgroundScope))
        runCurrent()
        driveNorth(h, 0.0, 7_000.0)

        assertEquals(listOf("ahead", "ahead", "ahead"), h.presenter.alerts.map { it.camera.id })
        assertEquals(listOf(1_000, 500, 200), h.presenter.alerts.map { it.thresholdM })
        assertTrue(h.presenter.alerts.first().isFirstForCamera)
        assertEquals(72, h.presenter.alerts.first().speedKmh)
        assertEquals(3, h.history.recorded.size)
        assertTrue(h.history.recorded.all { it.second == "session-1" })
        h.manager.stop(StopReason.USER_IN_APP)
    }

    @Test
    fun `high accuracy GPS is used only near the relevant camera`() = runTest {
        val h = Harness(this, listOf(ahead, opposite, behind, crossing))
        h.manager.start(backgroundScope)
        runCurrent()
        driveNorth(h, 0.0, 7_000.0)

        val highAccuracyStarts = h.planLog.filter { it.second == LocationPriority.HIGH_ACCURACY }
        assertEquals("exactly one precise-GPS window", 1, highAccuracyStarts.size)
        val startDistance = highAccuracyStarts.single().first
        assertTrue("precise GPS started at $startDistance m", startDistance in 1_000.0..2_200.0)
        // Precise GPS is released as soon as the camera is reached/passed.
        val startIndex = h.planLog.indexOf(highAccuracyStarts.single())
        val release = h.planLog.getOrNull(startIndex + 1)
        assertTrue("precise GPS was never released", release != null)
        assertTrue(release!!.second != LocationPriority.HIGH_ACCURACY)
        assertTrue("released at ${release.first} m before the camera", release.first <= 50.0)
        assertTrue(h.planLog.drop(startIndex + 1).none { it.second == LocationPriority.HIGH_ACCURACY })
        assertFalse(h.source.appliedPlans.last().requestsHighAccuracy)
        h.manager.stop(StopReason.USER_IN_APP)
    }

    @Test
    fun `stop removes every location callback and returns to idle`() = runTest {
        val h = Harness(this, listOf(ahead))
        h.manager.start(backgroundScope)
        runCurrent()
        driveNorth(h, 2_500.0, 3_300.0)
        assertEquals(TrackingMode.CLOSE, h.controller.mode)

        h.manager.stop(StopReason.USER_NOTIFICATION)
        runCurrent()
        assertEquals(1, h.source.stopCount)
        assertEquals(SessionState.Idle, h.manager.state.value)
        assertEquals(1, h.presenter.released)
        assertFalse(h.manager.isRunning)

        // Late fixes after stop are ignored and never re-register requests.
        val plans = h.source.appliedPlans.size
        h.source.emit(Fixtures.fix(Fixtures.point(3_400.0), elapsedMs = testScheduler.currentTime))
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(plans, h.source.appliedPlans.size)
        assertEquals(StopReason.USER_NOTIFICATION, h.manager.lastStopReason)
    }

    @Test
    fun `a new session starts with fresh passed-camera state`() = runTest {
        val h = Harness(this, listOf(ahead))
        h.manager.start(backgroundScope)
        runCurrent()
        driveNorth(h, 0.0, 4_400.0)
        h.manager.stop(StopReason.USER_IN_APP)
        assertEquals(3, h.presenter.alerts.size)

        h.manager.start(backgroundScope)
        runCurrent()
        driveNorth(h, 0.0, 4_400.0)
        assertEquals(6, h.presenter.alerts.size)
        h.manager.stop(StopReason.USER_IN_APP)
    }

    @Test
    fun `parked for a long time prompts and then requests an automatic stop`() = runTest {
        val h = Harness(this, listOf(ahead))
        val events = ArrayList<SessionEvent>()
        backgroundScope.launch { h.manager.events.collect { events += it } }
        h.manager.start(backgroundScope)
        runCurrent()
        val parked = Fixtures.point(0.0)
        // A fix every 60 s while parked (balanced fallback), for 31 minutes.
        repeat(32) {
            h.source.emit(Fixtures.fix(parked, elapsedMs = testScheduler.currentTime, speedMps = 0f))
            runCurrent()
            advanceTimeBy(60_000L)
            runCurrent()
        }
        assertEquals(1, h.presenter.driveEndShown)
        assertEquals(listOf<SessionEvent>(SessionEvent.AutoStopRequested(StopReason.STATIONARY_TIMEOUT)), events)
        val state = h.manager.state.value as SessionState.Active
        assertTrue(state.isStationary)
        assertTrue(state.awaitingDriveEndConfirmation)
        h.manager.stop(StopReason.STATIONARY_TIMEOUT)
    }

    @Test
    fun `traffic jam does not trigger the drive-ended prompt`() = runTest {
        val h = Harness(this, listOf(ahead))
        h.manager.start(backgroundScope)
        runCurrent()
        // 40 minutes of stop-and-go at ~1 m/s.
        driveNorth(h, 0.0, 2_400.0, speedMps = 1.0, stepMs = 20_000L)
        assertEquals(0, h.presenter.driveEndShown)
        h.manager.stop(StopReason.USER_IN_APP)
    }

    @Test
    fun `short GPS loss near a camera is bridged by dead reckoning`() = runTest {
        val h = Harness(this, listOf(ahead))
        h.manager.start(backgroundScope)
        runCurrent()
        driveNorth(h, 2_000.0, 3_500.0) // 1000 m and 500 m bands fire; ~520 m before the camera
        assertEquals(2, h.presenter.alerts.size)
        // Tunnel: no fixes for 20 s while driving at 20 m/s (400 m).
        advanceTimeBy(20_000L)
        runCurrent()
        assertEquals(3, h.presenter.alerts.size)
        assertTrue(h.presenter.alerts.last().isEstimatedPosition)
        assertEquals(200, h.presenter.alerts.last().thresholdM)
        h.manager.stop(StopReason.USER_IN_APP)
    }

    @Test
    fun `uses a recent cached location at start`() = runTest {
        val h = Harness(this, listOf(ahead))
        h.source.lastKnown = Fixtures.fix(Fixtures.point(3_300.0), elapsedMs = 0L)
        h.manager.start(backgroundScope)
        runCurrent()
        val state = h.manager.state.value as SessionState.Active
        assertEquals(1, state.nearbyCameraCount)
        h.manager.stop(StopReason.USER_IN_APP)
    }

    @Test
    fun `camera distance helper sanity`() {
        val d = GeoMath.distanceMeters(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, ahead.latitude, ahead.longitude)
        assertEquals(4_000.0, d, 1.0)
    }
}
