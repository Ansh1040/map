package com.drivealert.app.testutil

import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.domain.geo.BoundingBox
import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.geo.GridCell
import com.drivealert.app.domain.model.Camera
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

class FakeCameraRepository(initial: List<Camera> = emptyList()) : CameraRepository {
    private val cameras = initial.associateBy { it.id }.toMutableMap()
    override val dataVersion: StateFlow<Long> = MutableStateFlow(0L)

    override suspend fun camerasInCells(cellIds: List<Long>): List<Camera> {
        val cells = cellIds.toSet()
        return cameras.values.filter { GridCell.cellId(it.latitude, it.longitude) in cells }
    }

    override fun observeCamera(id: String): Flow<Camera?> = flowOf(cameras[id])
    override suspend fun getCamera(id: String): Camera? = cameras[id]
    override suspend fun camerasInBounds(bounds: BoundingBox, limit: Int): List<Camera> =
        cameras.values.filter { bounds.contains(it.latitude, it.longitude) }.take(limit)

    override suspend fun camerasNear(latitude: Double, longitude: Double, radiusM: Double): List<Camera> =
        cameras.values.filter { GeoMath.distanceMeters(latitude, longitude, it.latitude, it.longitude) <= radiusM }

    override suspend fun search(query: String, limit: Int): List<Camera> = emptyList()
    override fun observeActiveCount(): Flow<Int> = flowOf(cameras.size)

    override suspend fun applyDelta(packageId: String, replaceAll: Boolean, upserts: List<Camera>, removedIds: List<String>) {
        upserts.forEach { cameras[it.id] = it }
        removedIds.forEach { cameras.remove(it) }
    }

    override suspend fun removeTestData() = Unit
    override suspend fun purgeExpired(nowMillis: Long): Int = 0
}
