package com.drivealert.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drivealert.app.cameras.RoomCameraRepository
import com.drivealert.app.database.DriveAlertDatabase
import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.model.DataSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room on the real device SQLite (OnePlus 13 / emulator). Synthetic test data only. */
@RunWith(AndroidJUnit4::class)
class CameraDatabaseInstrumentedTest {
    private lateinit var db: DriveAlertDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DriveAlertDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun spatialQueryOverThousandsOfCameras() = runBlocking {
        val repository = RoomCameraRepository(db.cameraDao())
        val origin = 12.9716 to 77.5946
        val cameras = (0 until 5_000).map { i ->
            val p = GeoMath.destination(origin.first, origin.second, (i * 37 % 360).toDouble(), (i % 100) * 1_000.0)
            Camera(
                id = "TEST-$i", latitude = p.latitude, longitude = p.longitude, type = CameraType.FIXED_SPEED,
                dataSource = DataSource.TEST_ONLY_SYNTHETIC, isTestData = true,
            )
        }
        repository.applyDelta("test", replaceAll = true, upserts = cameras, removedIds = emptyList())
        val start = System.nanoTime()
        val near = repository.camerasNear(origin.first, origin.second, 5_500.0)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // Rings at 0..5 km (radius 5.5 km): i % 100 in 0..5 -> 6 of every 100 cameras.
        assertEquals(300, near.size)
        assert(elapsedMs < 200) { "spatial lookup took $elapsedMs ms" }
    }
}
