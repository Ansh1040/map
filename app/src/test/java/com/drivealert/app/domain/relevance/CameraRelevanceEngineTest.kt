package com.drivealert.app.domain.relevance

import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.Directionality
import com.drivealert.app.domain.model.Heading
import com.drivealert.app.domain.model.HeadingQuality
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.model.RoadContext
import com.drivealert.app.domain.model.RoadMatch
import com.drivealert.app.domain.model.VerificationStatus
import com.drivealert.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRelevanceEngineTest {
    private val engine = CameraRelevanceEngine()
    private val north = Heading(0.0, HeadingQuality.HIGH)
    private val now = 1_000_000L

    private fun evaluate(
        camera: Camera,
        fix: LocationFix = Fixtures.fix(Fixtures.point(0.0), elapsedMs = 0),
        heading: Heading? = north,
        includeUnverified: Boolean = false,
        roadMatch: RoadMatch? = null,
    ): CameraEvaluation? = engine.evaluate(fix, heading, listOf(camera), now, includeUnverified, roadMatch).singleOrNull()

    @Test
    fun `camera ahead in the same direction is alertable`() {
        val result = evaluate(Fixtures.camera("ahead", Fixtures.point(800.0)))!!
        assertTrue(result.isAlertable)
        assertTrue(result.isApproachCandidate)
        assertNull(result.rejectReason)
        assertEquals(AheadState.AHEAD, result.ahead)
        assertTrue(result.confidence >= 0.55)
    }

    @Test
    fun `camera behind is rejected`() {
        val result = evaluate(Fixtures.camera("behind", Fixtures.point(-300.0)))!!
        assertFalse(result.isAlertable)
        assertFalse(result.isApproachCandidate)
        assertEquals(RejectReason.BEHIND, result.rejectReason)
    }

    @Test
    fun `opposite carriageway camera is rejected even when it is ahead`() {
        // India drives on the left: the opposite carriageway is to the driver's right.
        val camera = Fixtures.camera("opposite", Fixtures.point(600.0, rightM = 25.0), bearingDeg = 180.0)
        val result = evaluate(camera)!!
        assertFalse(result.isAlertable)
        assertFalse(result.isApproachCandidate)
        assertEquals(RejectReason.OPPOSITE_DIRECTION, result.rejectReason)
    }

    @Test
    fun `camera on a crossing road is rejected`() {
        val camera = Fixtures.camera("crossing", Fixtures.point(400.0, rightM = 20.0), bearingDeg = 90.0)
        assertEquals(RejectReason.CROSSING_ROAD, evaluate(camera)!!.rejectReason)
    }

    @Test
    fun `parallel service lane camera is ignored when laterally offset`() {
        val camera = Fixtures.camera("service", Fixtures.point(300.0, rightM = -35.0), roadContext = RoadContext.SERVICE_LANE)
        val result = evaluate(camera)!!
        assertFalse(result.isAlertable)
        assertEquals(RejectReason.DIFFERENT_ROAD, result.rejectReason)
    }

    @Test
    fun `ambiguous road context is not alerted from far away`() {
        val camera = Fixtures.camera("flyover", Fixtures.point(900.0), roadContext = RoadContext.FLYOVER)
        val result = evaluate(camera)!!
        assertFalse(result.isAlertable)
        assertTrue("keeps precise tracking so it can decide later", result.isApproachCandidate)
        assertEquals(RejectReason.LOW_CONFIDENCE, result.rejectReason)
    }

    @Test
    fun `ambiguous road context alerts close up with precise aligned GPS`() {
        val camera = Fixtures.camera("flyover", Fixtures.point(300.0), roadContext = RoadContext.FLYOVER)
        assertTrue(evaluate(camera)!!.isAlertable)
    }

    @Test
    fun `ambiguous road context needs good accuracy`() {
        val camera = Fixtures.camera("flyover", Fixtures.point(300.0), roadContext = RoadContext.FLYOVER)
        val fix = Fixtures.fix(Fixtures.point(0.0), elapsedMs = 0, accuracyM = 30f)
        assertEquals(RejectReason.POOR_GPS, evaluate(camera, fix)!!.rejectReason)
    }

    @Test
    fun `main-road camera close but far to the side is treated as another road`() {
        val camera = Fixtures.camera("side", Fixtures.point(100.0, rightM = 70.0))
        val result = evaluate(camera)!!
        assertFalse(result.isAlertable)
    }

    @Test
    fun `inaccurate GPS never alerts`() {
        val fix = Fixtures.fix(Fixtures.point(0.0), elapsedMs = 0, accuracyM = 150f)
        val result = evaluate(Fixtures.camera("ahead", Fixtures.point(600.0)), fix)!!
        assertFalse(result.isAlertable)
        assertEquals(RejectReason.POOR_GPS, result.rejectReason)
        assertTrue(result.isApproachCandidate)
    }

    @Test
    fun `no heading means no alert, but nearby cameras justify precise tracking`() {
        val near = evaluate(Fixtures.camera("near", Fixtures.point(900.0)), heading = null)!!
        assertEquals(RejectReason.NO_HEADING, near.rejectReason)
        assertTrue(near.isApproachCandidate)
        val far = evaluate(Fixtures.camera("far", Fixtures.point(3_000.0)), heading = null)!!
        assertFalse(far.isApproachCandidate)
    }

    @Test
    fun `unverified reports alert only when the user opts in`() {
        val camera = Fixtures.camera("report", Fixtures.point(500.0), status = VerificationStatus.PENDING_VERIFICATION)
        assertEquals(RejectReason.NOT_VERIFIED, evaluate(camera)!!.rejectReason)
        assertTrue(evaluate(camera, includeUnverified = true)!!.isAlertable)
    }

    @Test
    fun `expired, inactive, rejected and out-of-range cameras are skipped`() {
        assertNull(evaluate(Fixtures.camera("expired", Fixtures.point(500.0), expiresAtMillis = now - 1)))
        assertNull(evaluate(Fixtures.camera("inactive", Fixtures.point(500.0), isActive = false)))
        assertNull(evaluate(Fixtures.camera("rejected", Fixtures.point(500.0), status = VerificationStatus.REJECTED)))
        assertNull(evaluate(Fixtures.camera("far", Fixtures.point(6_000.0))))
    }

    @Test
    fun `unknown direction lowers confidence but still alerts when aligned`() {
        val known = evaluate(Fixtures.camera("known", Fixtures.point(500.0)))!!
        val unknown = evaluate(Fixtures.camera("unknown", Fixtures.point(500.0), bearingDeg = null, directionality = Directionality.UNKNOWN))!!
        assertTrue(unknown.isAlertable)
        assertTrue(unknown.confidence < known.confidence)
    }

    @Test
    fun `map matching on a different segment rejects the camera`() {
        val camera = Fixtures.camera("segment", Fixtures.point(500.0), roadSegmentId = "seg-A")
        assertEquals(RejectReason.DIFFERENT_ROAD, evaluate(camera, roadMatch = RoadMatch(null, "seg-B"))!!.rejectReason)
        assertTrue(evaluate(camera, roadMatch = RoadMatch(null, "seg-A"))!!.isAlertable)
    }

    @Test
    fun `elevation mismatch with precise vertical fix lowers confidence`() {
        val camera = Fixtures.camera("flyover-cam", Fixtures.point(300.0), elevationM = 950.0)
        val ground = Fixtures.fix(Fixtures.point(0.0), elapsedMs = 0).copy(altitudeM = 920.0, verticalAccuracyM = 3f)
        val level = Fixtures.fix(Fixtures.point(0.0), elapsedMs = 0).copy(altitudeM = 948.0, verticalAccuracyM = 3f)
        val mismatch = evaluate(camera, ground)!!
        val match = evaluate(camera, level)!!
        assertTrue(mismatch.confidence < match.confidence)
    }

    @Test
    fun `results are sorted nearest first`() {
        val cameras = listOf(
            Fixtures.camera("c3", Fixtures.point(3_000.0)),
            Fixtures.camera("c1", Fixtures.point(500.0)),
            Fixtures.camera("c2", Fixtures.point(1_500.0)),
        )
        val ids = engine.evaluate(Fixtures.fix(Fixtures.point(0.0), 0), north, cameras, now, false).map { it.camera.id }
        assertEquals(listOf("c1", "c2", "c3"), ids)
    }
}
