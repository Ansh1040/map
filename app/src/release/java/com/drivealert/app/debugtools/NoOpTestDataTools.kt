package com.drivealert.app.debugtools

import com.drivealert.app.data.testdata.TestDataTools
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/** Release builds never contain or load synthetic test cameras. */
@Singleton
class NoOpTestDataTools @Inject constructor() : TestDataTools {
    override val isAvailable: Boolean = false
    override suspend fun loadSyntheticCamerasAround(latitude: Double, longitude: Double, headingDeg: Double): Int = 0
    override suspend fun clearSyntheticCameras() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReleaseToolsModule {
    @Binds
    abstract fun bindTestDataTools(impl: NoOpTestDataTools): TestDataTools
}
