package com.drivealert.app.domain.relevance

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.Directionality
import com.drivealert.app.domain.model.HeadingQuality
import com.drivealert.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BearingMatcherTest {
    private val matcher = BearingMatcher()
    private val anywhere = Fixtures.point(500.0)

    @Test
    fun `one-direction camera matches same direction within tolerance`() {
        val camera = Fixtures.camera("c", anywhere, bearingDeg = 0.0)
        assertEquals(DirectionMatch.MATCH, matcher.matchDirection(10.0, HeadingQuality.HIGH, camera))
        assertEquals(DirectionMatch.MATCH, matcher.matchDirection(320.0, HeadingQuality.HIGH, camera))
    }

    @Test
    fun `opposite carriageway is detected`() {
        val camera = Fixtures.camera("c", anywhere, bearingDeg = 0.0)
        assertEquals(DirectionMatch.OPPOSITE, matcher.matchDirection(180.0, HeadingQuality.HIGH, camera))
        assertEquals(DirectionMatch.OPPOSITE, matcher.matchDirection(200.0, HeadingQuality.HIGH, camera))
    }

    @Test
    fun `crossing road is detected`() {
        val camera = Fixtures.camera("c", anywhere, bearingDeg = 0.0)
        assertEquals(DirectionMatch.CROSSING, matcher.matchDirection(90.0, HeadingQuality.HIGH, camera))
        assertEquals(DirectionMatch.CROSSING, matcher.matchDirection(270.0, HeadingQuality.HIGH, camera))
    }

    @Test
    fun `coarse heading widens the tolerance`() {
        val camera = Fixtures.camera("c", anywhere, bearingDeg = 0.0)
        assertEquals(DirectionMatch.CROSSING, matcher.matchDirection(55.0, HeadingQuality.HIGH, camera))
        assertEquals(DirectionMatch.MATCH, matcher.matchDirection(55.0, HeadingQuality.LOW, camera))
    }

    @Test
    fun `bidirectional camera matches both directions but not crossing traffic`() {
        val camera = Fixtures.camera("c", anywhere, bearingDeg = 0.0, directionality = Directionality.BOTH_DIRECTIONS)
        assertEquals(DirectionMatch.MATCH, matcher.matchDirection(0.0, HeadingQuality.HIGH, camera))
        assertEquals(DirectionMatch.MATCH, matcher.matchDirection(180.0, HeadingQuality.HIGH, camera))
        assertEquals(DirectionMatch.CROSSING, matcher.matchDirection(90.0, HeadingQuality.HIGH, camera))
    }

    @Test
    fun `unknown direction never rejects`() {
        val camera = Fixtures.camera("c", anywhere, bearingDeg = null, directionality = Directionality.UNKNOWN)
        assertEquals(DirectionMatch.UNKNOWN, matcher.matchDirection(123.0, HeadingQuality.HIGH, camera))
    }

    @Test
    fun `ahead versus behind versus beside`() {
        val origin = Fixtures.point(0.0)
        fun classify(target: com.drivealert.app.domain.geo.GeoPoint, heading: Double = 0.0): AheadState {
            val pos = GeoMath.relativePosition(origin.latitude, origin.longitude, heading, target.latitude, target.longitude)
            return matcher.classifyAhead(pos, accuracyM = 5f, lateralToleranceM = 30.0 + pos.distanceM * 0.15)
        }
        assertEquals(AheadState.AHEAD, classify(Fixtures.point(500.0)))
        assertEquals(AheadState.AHEAD, classify(Fixtures.point(800.0, rightM = 150.0))) // gentle curve
        assertEquals(AheadState.BEHIND, classify(Fixtures.point(-100.0)))
        assertEquals(AheadState.BEHIND, classify(Fixtures.point(500.0), heading = 180.0))
        assertEquals(AheadState.BESIDE, classify(Fixtures.point(50.0, rightM = 300.0)))
    }

    @Test
    fun `lenient approach cone is wider than the strict alert cone`() {
        val origin = Fixtures.point(0.0)
        val target = Fixtures.point(1_000.0, rightM = 1_000.0) // 45 degrees off the heading
        val pos = GeoMath.relativePosition(origin.latitude, origin.longitude, 0.0, target.latitude, target.longitude)
        assertTrue(matcher.isRoughlyAhead(pos))
        assertEquals(AheadState.BESIDE, matcher.classifyAhead(pos, 5f, 30.0 + pos.distanceM * 0.15))
        val behind = GeoMath.relativePosition(origin.latitude, origin.longitude, 180.0, target.latitude, target.longitude)
        assertFalse(matcher.isRoughlyAhead(behind))
    }
}
