package com.drivealert.app.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drivealert.app.cameras.RoomCameraRepository
import com.drivealert.app.data.history.AlertHistoryRepository
import com.drivealert.app.domain.geo.GridCell
import com.drivealert.app.domain.model.CameraAlert
import com.drivealert.app.testutil.Fixtures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Room schema, spatial-index queries and history storage (Robolectric, in-memory database). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CameraDatabaseTest {
    private lateinit var db: DriveAlertDatabase
    private lateinit var repository: RoomCameraRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DriveAlertDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomCameraRepository(db.cameraDao())
    }

    @After
    fun tearDown() = db.close()

    private val near = Fixtures.camera("near", Fixtures.point(1_000.0))
    private val edge = Fixtures.camera("edge", Fixtures.point(4_800.0))
    private val far = Fixtures.camera("far", Fixtures.point(30_000.0))

    @Test
    fun spatialIndexReturnsOnlyRequestedCells() = runTest {
        repository.applyDelta("pkg", replaceAll = false, upserts = listOf(near, edge, far), removedIds = emptyList())
        val cells = GridCell.cellsCovering(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 5_000.0)
        val ids = repository.camerasInCells(cells).map { it.id }.toSet()
        assertTrue("near" in ids && "edge" in ids)
        assertTrue("far" !in ids)
        assertEquals(setOf("near", "edge"), repository.camerasNear(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 5_000.0).map { it.id }.toSet())
    }

    @Test
    fun roundTripPreservesEveryField() = runTest {
        repository.applyDelta("pkg", false, listOf(near), emptyList())
        assertEquals(near.copy(packageId = "pkg"), repository.getCamera("near"))
    }

    @Test
    fun incrementalDeltaAndFullReplace() = runTest {
        repository.applyDelta("pkg", false, listOf(near, edge), emptyList())
        repository.applyDelta("pkg", false, listOf(near.copy(speedLimitKmh = 50)), removedIds = listOf("edge"))
        assertEquals(50, repository.getCamera("near")?.speedLimitKmh)
        assertNull(repository.getCamera("edge"))

        repository.applyDelta("pkg", replaceAll = true, upserts = listOf(far), removedIds = emptyList())
        assertNull(repository.getCamera("near"))
        assertEquals(1, repository.observeActiveCount().first())
        assertTrue(repository.dataVersion.value >= 3)
    }

    @Test
    fun inactiveAndExpiredCamerasAreExcluded() = runTest {
        val inactive = Fixtures.camera("inactive", Fixtures.point(500.0), isActive = false)
        val temporary = Fixtures.camera("temporary", Fixtures.point(600.0), expiresAtMillis = 1_000L)
        repository.applyDelta("pkg", false, listOf(near, inactive, temporary), emptyList())
        val ids = repository.camerasNear(Fixtures.ORIGIN_LAT, Fixtures.ORIGIN_LON, 5_000.0).map { it.id }
        assertTrue("inactive" !in ids)
        assertEquals(1, repository.purgeExpired(nowMillis = 2_000L))
        assertNull(repository.getCamera("temporary"))
    }

    @Test
    fun searchMatchesRoadCityAndState() = runTest {
        repository.applyDelta("pkg", false, listOf(near), emptyList())
        assertEquals(listOf("near"), repository.search("Test city").map { it.id })
        assertTrue(repository.search("x").isEmpty())
    }

    @Test
    fun historyStoresNoCoordinatesAndCanBeCleared() = runTest {
        val history = AlertHistoryRepository(db.alertHistoryDao())
        val alert = CameraAlert(near, 500, 500, 48, false, true, false, timestampMillis = 10_000L)
        history.record(alert, "session")
        val items = history.observeHistory().first()
        assertEquals(1, items.size)
        assertEquals("near", items.single().cameraId)
        assertEquals(1, history.prune(retentionDays = 0, nowMillis = 10L * 24 * 3_600_000L))
        history.record(alert, "session")
        history.clear()
        assertTrue(history.observeHistory().first().isEmpty())
    }

    @Test
    fun reportsKeepAnAuditTrail() = runTest {
        val dao = db.reportDao()
        val id = dao.insertWithAudit(
            CameraReportEntity(
                remoteId = null, reportType = "NEW_CAMERA", cameraId = null, latitude = 1.0, longitude = 2.0,
                bearingDeg = null, speedLimitKmh = null, cameraType = "FIXED_SPEED", note = null,
                createdAt = 5_000L, status = "PENDING_UPLOAD", expiresAt = 6_000L, lastError = null,
            ),
            event = "CREATED",
            detail = null,
        )
        assertEquals(listOf("CREATED"), dao.auditFor(id).map { it.event })
        assertEquals(1, dao.expireOld(nowMillis = 7_000L))
        assertEquals("EXPIRED", dao.getById(id)?.status)
    }
}
