package com.drivealert.app.debugtools

import com.drivealert.app.data.testdata.DriveSimulator
import com.drivealert.app.data.testdata.SimulationState
import com.drivealert.app.data.testdata.TestDataTools
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Release builds never contain or load synthetic test cameras. */
@Singleton
class NoOpTestDataTools @Inject constructor() : TestDataTools {
    override val isAvailable: Boolean = false
    override suspend fun loadSyntheticCamerasAround(latitude: Double, longitude: Double, headingDeg: Double): Int = 0
    override suspend fun clearSyntheticCameras() = Unit
}

/** Release builds have no mock-GPS drive simulator. */
@Singleton
class NoOpDriveSimulator @Inject constructor() : DriveSimulator {
    override val isAvailable: Boolean = false
    override val state: StateFlow<SimulationState> = MutableStateFlow(SimulationState.Idle)
    override fun start() = Unit
    override fun stop() = Unit
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReleaseToolsModule {
    @Binds
    abstract fun bindTestDataTools(impl: NoOpTestDataTools): TestDataTools

    @Binds
    abstract fun bindDriveSimulator(impl: NoOpDriveSimulator): DriveSimulator
}
