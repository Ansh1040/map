package com.drivealert.app.data.testdata

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.model.Heading
import com.drivealert.app.domain.model.HeadingQuality
import com.drivealert.app.domain.relevance.CameraRelevanceEngine
import com.drivealert.app.domain.relevance.RejectReason
import com.drivealert.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validates the TEST-ONLY synthetic dataset shipped in the debug source set. */
class SyntheticCameraDatasetTest {
    private val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(SyntheticCameraDataset.ASSET_NAME)) {
        "debug asset not on the unit-test classpath"
    }.bufferedReader().use { it.readText() }

    private val file = SyntheticCameraDataset.parse(text)
    private val heading = 30.0
    private val cameras = SyntheticCameraDataset.materialize(file, Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, heading, nowMillis = 0L)

    @Test
    fun `dataset is clearly labelled test-only`() {
        assertTrue(file.warning.contains("TEST-ONLY"))
        assertEquals(10, file.cameras.size)
        assertTrue(cameras.all { it.isTestData && it.dataSource == DataSource.TEST_ONLY_SYNTHETIC && it.id.startsWith("TEST-") })
        assertTrue(cameras.all { it.roadName!!.startsWith("TEST ONLY") })
    }

    @Test
    fun `positions are placed relative to the anchor heading`() {
        val first = cameras.first { it.id == "TEST-001" }
        assertEquals(1_500.0, GeoMath.distanceMeters(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, first.latitude, first.longitude), 1.0)
        assertEquals(heading, GeoMath.initialBearingDeg(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, first.latitude, first.longitude), 0.5)
        assertEquals(heading, first.bearingDeg!!, 1e-9)
        assertEquals(GeoMath.normalizeBearing(heading + 180.0), cameras.first { it.id == "TEST-002" }.bearingDeg!!, 1e-9)
    }

    @Test
    fun `scenario cameras produce their documented outcome`() {
        val engine = CameraRelevanceEngine()
        val h = Heading(heading, HeadingQuality.HIGH)
        fun outcome(id: String, fromAheadM: Double): RejectReason? {
            val p = Fixtures.point(fromAheadM, headingDeg = heading)
            val fix = Fixtures.fix(p, 0L, bearingDeg = heading.toFloat())
            return engine.evaluate(fix, h, cameras.filter { it.id == id }, 0L, includeUnverified = false).single().rejectReason
        }
        assertEquals(null, outcome("TEST-001", 1_000.0))
        assertEquals(RejectReason.OPPOSITE_DIRECTION, outcome("TEST-002", 2_500.0))
        assertEquals(RejectReason.CROSSING_ROAD, outcome("TEST-003", 3_800.0))
        assertEquals(RejectReason.DIFFERENT_ROAD, outcome("TEST-004", 4_900.0))
        assertEquals(RejectReason.BEHIND, outcome("TEST-005", 0.0))
        assertEquals(null, outcome("TEST-006", 6_000.0))
        assertEquals(RejectReason.NOT_VERIFIED, outcome("TEST-007", 7_000.0))
        assertEquals(null, outcome("TEST-008", 8_500.0))
        assertEquals(RejectReason.LOW_CONFIDENCE, outcome("TEST-009", 9_700.0))
        assertEquals(null, outcome("TEST-009", 10_200.0))
        assertEquals(null, outcome("TEST-010", 11_500.0))
    }
}
