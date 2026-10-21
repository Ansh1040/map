package com.drivealert.app.domain.cameras

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.VerificationStatus
import com.drivealert.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyCamerasTest {
    private val now = 1_700_000_000_000L

    /** Ranks [cameras] as seen by a user standing at the fixture origin. */
    private fun rank(
        cameras: List<Camera>,
        includeUnverified: Boolean = false,
        limit: Int = NearbyCameras.DEFAULT_LIMIT,
        maxDistanceM: Double = NearbyCameras.DEFAULT_RADIUS_M,
    ): List<NearbyCamera> = NearbyCameras.rank(
        latitude = Fixtures.ORIGIN_LAT,
        longitude = Fixtures.ORIGIN_LON,
        cameras = cameras,
        nowMillis = now,
        includeUnverified = includeUnverified,
        limit = limit,
        maxDistanceM = maxDistanceM,
    )

    private fun List<NearbyCamera>.ids(): List<String> = map { it.camera.id }

    @Test
    fun `nearest cameras come first`() {
        val ranked = rank(
            listOf(
                Fixtures.camera("far", Fixtures.point(3_000.0)),
                Fixtures.camera("near", Fixtures.point(400.0, headingDeg = 90.0)),
                Fixtures.camera("middle", Fixtures.point(1_500.0, rightM = 200.0, headingDeg = 225.0)),
            ),
        )
        assertEquals(listOf("near", "middle", "far"), ranked.ids())
        assertEquals(400.0, ranked.first().distanceM, 1.0)
        assertEquals(3_000.0, ranked.last().distanceM, 1.0)
    }

    @Test
    fun `cameras beyond the maximum distance are left out`() {
        val cameras = listOf(
            Fixtures.camera("inside", Fixtures.point(4_900.0)),
            Fixtures.camera("outside", Fixtures.point(5_100.0)),
        )
        assertEquals(listOf("inside"), rank(cameras, maxDistanceM = 5_000.0).ids())

        // Without an explicit cut-off, NearbyCameras.DEFAULT_RADIUS_M (25 km) applies.
        val regional = listOf(
            Fixtures.camera("24km", Fixtures.point(24_000.0)),
            Fixtures.camera("26km", Fixtures.point(26_000.0)),
        )
        assertEquals(listOf("24km"), rank(regional).ids())
    }

    @Test
    fun `only the nearest cameras up to the limit are returned`() {
        // Farthest first, so the order of the result comes from the ranking, not the input.
        val cameras = (1..15).map { i -> Fixtures.camera("c$i", Fixtures.point(i * 100.0)) }.reversed()
        assertEquals((1..NearbyCameras.DEFAULT_LIMIT).map { "c$it" }, rank(cameras).ids())
        assertEquals(listOf("c1", "c2", "c3"), rank(cameras, limit = 3).ids())
    }

    @Test
    fun `unverified cameras are included only when the user opts in`() {
        val cameras = listOf(
            Fixtures.camera("verified", Fixtures.point(100.0)),
            Fixtures.camera("community", Fixtures.point(200.0), status = VerificationStatus.COMMUNITY_CONFIRMED),
            Fixtures.camera("pending", Fixtures.point(300.0), status = VerificationStatus.PENDING_VERIFICATION),
            Fixtures.camera("disputed", Fixtures.point(400.0), status = VerificationStatus.DISPUTED),
            Fixtures.camera("rejected", Fixtures.point(500.0), status = VerificationStatus.REJECTED),
        )
        assertEquals(listOf("verified", "community"), rank(cameras, includeUnverified = false).ids())
        // Opting in adds pending and disputed reports; rejected ones never appear.
        assertEquals(listOf("verified", "community", "pending", "disputed"), rank(cameras, includeUnverified = true).ids())
    }

    @Test
    fun `expired and inactive cameras are left out`() {
        val cameras = listOf(
            Fixtures.camera("active", Fixtures.point(100.0)),
            Fixtures.camera("expired", Fixtures.point(200.0), expiresAtMillis = now - 1_000L),
            Fixtures.camera("expires-later", Fixtures.point(300.0), expiresAtMillis = now + 60_000L),
            Fixtures.camera("inactive", Fixtures.point(400.0), isActive = false),
        )
        assertEquals(listOf("active", "expires-later"), rank(cameras, includeUnverified = true).ids())
    }

    @Test
    fun `bearing points from the user to the camera`() {
        val ranked = rank(
            listOf(
                Fixtures.camera("north", Fixtures.point(1_000.0)),
                Fixtures.camera("east", Fixtures.point(1_000.0, headingDeg = 90.0)),
            ),
        ).associateBy { it.camera.id }
        val north = ranked.getValue("north")
        val east = ranked.getValue("east")
        // Compared as angles: due north can come out as 359.99... instead of 0.
        assertEquals(0.0, GeoMath.angularDifferenceDeg(north.bearingFromUserDeg, 0.0), 0.5)
        assertEquals(0.0, GeoMath.angularDifferenceDeg(east.bearingFromUserDeg, 90.0), 0.5)
        assertEquals(1_000.0, north.distanceM, 1.0)
        assertEquals(1_000.0, east.distanceM, 1.0)
        assertEquals(0, NearbyCameras.compassSector(north.bearingFromUserDeg))
        assertEquals(2, NearbyCameras.compassSector(east.bearingFromUserDeg))
    }

    @Test
    fun `compass sectors are 45 degrees wide and centred on the eight directions`() {
        assertEquals(0, NearbyCameras.compassSector(0.0))
        assertEquals(0, NearbyCameras.compassSector(22.4))
        assertEquals(1, NearbyCameras.compassSector(22.6))
        assertEquals(2, NearbyCameras.compassSector(90.0))
        assertEquals(4, NearbyCameras.compassSector(180.0))
        assertEquals(7, NearbyCameras.compassSector(337.4))
        assertEquals(0, NearbyCameras.compassSector(337.6))
        assertEquals(0, NearbyCameras.compassSector(359.9))
    }
}
