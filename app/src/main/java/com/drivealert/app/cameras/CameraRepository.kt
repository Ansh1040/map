package com.drivealert.app.cameras

import com.drivealert.app.database.CameraDao
import com.drivealert.app.database.toDomain
import com.drivealert.app.database.toEntity
import com.drivealert.app.domain.geo.BoundingBox
import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.geo.GridCell
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.ports.CameraLookup
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

interface CameraRepository : CameraLookup {
    /** Increments whenever camera records change; the session cache listens to it. */
    val dataVersion: StateFlow<Long>

    fun observeCamera(id: String): Flow<Camera?>
    suspend fun getCamera(id: String): Camera?
    suspend fun camerasInBounds(bounds: BoundingBox, limit: Int = MAX_MAP_MARKERS): List<Camera>
    suspend fun camerasNear(latitude: Double, longitude: Double, radiusM: Double): List<Camera>
    suspend fun search(query: String, limit: Int = 20): List<Camera>
    fun observeActiveCount(): Flow<Int>

    suspend fun applyDelta(packageId: String, replaceAll: Boolean, upserts: List<Camera>, removedIds: List<String>)
    suspend fun removeTestData()
    suspend fun purgeExpired(nowMillis: Long): Int

    companion object {
        const val MAX_MAP_MARKERS = 300
    }
}

@Singleton
class RoomCameraRepository @Inject constructor(
    private val dao: CameraDao,
) : CameraRepository {
    private val version = MutableStateFlow(0L)
    override val dataVersion: StateFlow<Long> = version.asStateFlow()

    override suspend fun camerasInCells(cellIds: List<Long>): List<Camera> {
        if (cellIds.isEmpty()) return emptyList()
        // SQLite limits bound parameters; chunk large cell sets.
        return cellIds.chunked(500).flatMap { chunk -> dao.activeInCells(chunk) }.map { it.toDomain() }
    }

    override fun observeCamera(id: String): Flow<Camera?> = dao.observeById(id).map { it?.toDomain() }

    override suspend fun getCamera(id: String): Camera? = dao.getById(id)?.toDomain()

    override suspend fun camerasInBounds(bounds: BoundingBox, limit: Int): List<Camera> =
        dao.activeInBounds(bounds.minLatitude, bounds.maxLatitude, bounds.minLongitude, bounds.maxLongitude, limit)
            .map { it.toDomain() }

    override suspend fun camerasNear(latitude: Double, longitude: Double, radiusM: Double): List<Camera> =
        camerasInCells(GridCell.cellsCovering(latitude, longitude, radiusM)).filter {
            GeoMath.distanceMeters(latitude, longitude, it.latitude, it.longitude) <= radiusM
        }

    override suspend fun search(query: String, limit: Int): List<Camera> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        return dao.search(trimmed, limit).map { it.toDomain() }
    }

    override fun observeActiveCount(): Flow<Int> = dao.observeActiveCount()

    override suspend fun applyDelta(packageId: String, replaceAll: Boolean, upserts: List<Camera>, removedIds: List<String>) {
        val now = System.currentTimeMillis()
        // Records always belong to the package they arrived in, so a later replace removes them.
        dao.applyDelta(packageId, replaceAll, upserts.map { it.copy(packageId = packageId).toEntity(now) }, removedIds)
        version.value++
    }

    override suspend fun removeTestData() {
        dao.deleteTestData()
        version.value++
    }

    override suspend fun purgeExpired(nowMillis: Long): Int {
        val removed = dao.deleteExpired(nowMillis)
        if (removed > 0) version.value++
        return removed
    }
}
