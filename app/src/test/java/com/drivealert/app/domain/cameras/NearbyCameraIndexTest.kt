package com.drivealert.app.domain.cameras

import com.drivealert.app.testutil.Fixtures
import com.drivealert.app.testutil.InMemoryCameraLookup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyCameraIndexTest {
    private val cameras = (0 until 40).map { i -> Fixtures.camera("c$i", Fixtures.point(i * 500.0)) } // 0..19.5 km north

    @Test
    fun `returns only cameras inside the radius`() = runTest {
        val index = NearbyCameraIndex(InMemoryCameraLookup(cameras))
        val result = index.candidatesNear(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 5_200.0)
        assertEquals((0..10).map { "c$it" }.toSet(), result.map { it.id }.toSet())
    }

    @Test
    fun `database is queried only after moving several kilometres`() = runTest {
        val lookup = InMemoryCameraLookup(cameras)
        val index = NearbyCameraIndex(lookup)
        // 100 GPS updates over 3.5 km: one load.
        for (i in 0 until 100) {
            val p = Fixtures.point(i * 35.0)
            index.candidatesNear(p.latitude, p.longitude, 5_000.0)
        }
        assertEquals(1, lookup.calls)
        val far = Fixtures.point(4_500.0)
        index.candidatesNear(far.latitude, far.longitude, 5_000.0)
        assertEquals(2, lookup.calls)
        assertEquals(2, index.loadCount)
    }

    @Test
    fun `cache always covers the query radius before reloading`() = runTest {
        val index = NearbyCameraIndex(InMemoryCameraLookup(cameras))
        index.candidatesNear(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 5_000.0)
        // 3.9 km from the anchor (no reload yet): cameras up to 8.9 km must be visible.
        val p = Fixtures.point(3_900.0)
        val result = index.candidatesNear(p.latitude, p.longitude, 5_000.0).map { it.id }
        assertEquals(true, "c17" in result)
    }

    @Test
    fun `invalidate forces a reload`() = runTest {
        val lookup = InMemoryCameraLookup(cameras)
        val index = NearbyCameraIndex(lookup)
        index.candidatesNear(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 5_000.0)
        index.invalidate()
        index.candidatesNear(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 5_000.0)
        assertEquals(2, lookup.calls)
    }
}
