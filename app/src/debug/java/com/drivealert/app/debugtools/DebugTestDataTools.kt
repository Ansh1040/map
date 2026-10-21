package com.drivealert.app.debugtools

import android.content.Context
import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.data.testdata.SyntheticCameraDataset
import com.drivealert.app.data.testdata.TestDataTools
import com.drivealert.app.database.DataPackageDao
import com.drivealert.app.database.DataPackageEntity
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DEBUG BUILDS ONLY. Loads the TEST-ONLY synthetic scenario around the
 * tester's current position for drive testing. Not compiled into release.
 */
@Singleton
class DebugTestDataTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraRepository: CameraRepository,
    private val packageDao: DataPackageDao,
) : TestDataTools {
    override val isAvailable: Boolean = true

    override suspend fun loadSyntheticCamerasAround(latitude: Double, longitude: Double, headingDeg: Double): Int =
        withContext(Dispatchers.IO) {
            val text = context.assets.open(SyntheticCameraDataset.ASSET_NAME).bufferedReader().use { it.readText() }
            val file = SyntheticCameraDataset.parse(text)
            val now = System.currentTimeMillis()
            val cameras = SyntheticCameraDataset.materialize(file, latitude, longitude, headingDeg, now)
            cameraRepository.applyDelta(SyntheticCameraDataset.TEST_PACKAGE_ID, replaceAll = true, upserts = cameras, removedIds = emptyList())
            packageDao.upsert(
                DataPackageEntity(
                    id = SyntheticCameraDataset.TEST_PACKAGE_ID,
                    name = "TEST-ONLY synthetic scenario",
                    state = "Test",
                    city = null,
                    dataVersion = 1,
                    cameraCount = cameras.size,
                    lastUpdatedAt = now,
                    isInstalled = true,
                    coverageNote = "Synthetic test data - not real camera locations",
                    isTestData = true,
                ),
            )
            cameras.size
        }

    override suspend fun clearSyntheticCameras() {
        cameraRepository.removeTestData()
        packageDao.deleteTestData()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugToolsModule {
    @Binds
    abstract fun bindTestDataTools(impl: DebugTestDataTools): TestDataTools

    @Binds
    abstract fun bindDriveSimulator(impl: MockDriveSimulator): com.drivealert.app.data.testdata.DriveSimulator
}
