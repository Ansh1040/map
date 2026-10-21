package com.drivealert.app.domain.geo

import com.drivealert.app.testutil.Fixtures
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun `one degree of longitude on the equator is about 111 km`() {
        val d = GeoMath.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, d, 5.0)
    }

    @Test
    fun `distance is symmetric and zero for identical points`() {
        val a = Fixtures.point(0.0)
        val b = Fixtures.point(1_234.0, headingDeg = 37.0)
        assertEquals(GeoMath.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude), GeoMath.distanceMeters(b.latitude, b.longitude, a.latitude, a.longitude), 1e-6)
        assertEquals(0.0, GeoMath.distanceMeters(a.latitude, a.longitude, a.latitude, a.longitude), 1e-9)
    }

    @Test
    fun `short distances match planar geometry within half a metre`() {
        val start = Fixtures.point(0.0)
        val end = Fixtures.point(300.0, rightM = 400.0)
        assertEquals(500.0, GeoMath.distanceMeters(start.latitude, start.longitude, end.latitude, end.longitude), 0.5)
    }

    @Test
    fun `bearings to cardinal directions`() {
        assertEquals(0.0, GeoMath.initialBearingDeg(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(90.0, GeoMath.initialBearingDeg(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(180.0, GeoMath.initialBearingDeg(1.0, 0.0, 0.0, 0.0), 1e-6)
        assertEquals(270.0, GeoMath.initialBearingDeg(0.0, 1.0, 0.0, 0.0), 1e-6)
    }

    @Test
    fun `angular difference wraps around north`() {
        assertEquals(20.0, GeoMath.angularDifferenceDeg(350.0, 10.0), 1e-9)
        assertEquals(180.0, GeoMath.angularDifferenceDeg(0.0, 180.0), 1e-9)
        assertEquals(20.0, GeoMath.signedAngleDeg(350.0, 10.0), 1e-9)
        assertEquals(-20.0, GeoMath.signedAngleDeg(10.0, 350.0), 1e-9)
        assertEquals(10.0, GeoMath.normalizeBearing(370.0), 1e-9)
        assertEquals(350.0, GeoMath.normalizeBearing(-10.0), 1e-9)
    }

    @Test
    fun `destination round trip keeps distance and bearing`() {
        val p = GeoMath.destination(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 45.0, 1_000.0)
        assertEquals(1_000.0, GeoMath.distanceMeters(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, p.latitude, p.longitude), 0.01)
        assertEquals(45.0, GeoMath.initialBearingDeg(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, p.latitude, p.longitude), 0.01)
    }

    @Test
    fun `relative position splits into along-track and cross-track`() {
        val camera = Fixtures.point(1_000.0)
        val headingNorth = GeoMath.relativePosition(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 0.0, camera.latitude, camera.longitude)
        assertEquals(1_000.0, headingNorth.alongTrackM, 0.5)
        assertEquals(0.0, headingNorth.crossTrackM, 0.5)

        val headingEast = GeoMath.relativePosition(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 90.0, camera.latitude, camera.longitude)
        assertEquals(0.0, headingEast.alongTrackM, 1.0)
        assertEquals(-1_000.0, headingEast.crossTrackM, 1.0) // camera is on the left

        val behind = GeoMath.relativePosition(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 180.0, camera.latitude, camera.longitude)
        assertTrue(behind.alongTrackM < -999.0)
    }

    @Test
    fun `grid cells covering a radius include every point inside it`() {
        val cells = GridCell.cellsCovering(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 5_000.0).toSet()
        for (bearing in 0 until 360 step 15) {
            for (distance in listOf(0.0, 1_000.0, 2_500.0, 4_999.0)) {
                val p = GeoMath.destination(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, bearing.toDouble(), distance)
                assertTrue("missing cell for $bearing/$distance", GridCell.cellId(p.latitude, p.longitude) in cells)
            }
        }
    }

    @Test
    fun `bounding box contains the radius`() {
        val box = GeoMath.boundingBox(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 2_000.0)
        val east = Fixtures.point(1_999.0, headingDeg = 90.0)
        assertTrue(box.contains(east.latitude, east.longitude))
        assertTrue(abs(box.maxLatitude - box.minLatitude) > 0.03)
    }
}
