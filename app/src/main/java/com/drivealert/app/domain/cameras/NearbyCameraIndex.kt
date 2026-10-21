package com.drivealert.app.domain.cameras

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.geo.GridCell
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.ports.CameraLookup

data class IndexConfig(
    /** Radius of cameras loaded into memory around the cache anchor. */
    val cacheRadiusM: Double = 12_000.0,
    /** Reload once the driver is this far from the anchor. */
    val refreshAfterMovingM: Double = 4_000.0,
)

/**
 * In-memory spatial cache of nearby cameras.
 *
 * The database is only queried when the driver moves [IndexConfig.refreshAfterMovingM]
 * from the last load point (roughly every few minutes at highway speed); every
 * other GPS update is answered from an in-memory grid, touching only the few
 * cells around the driver.
 */
class NearbyCameraIndex(
    private val lookup: CameraLookup,
    private val config: IndexConfig = IndexConfig(),
) {
    private var anchorLat = Double.NaN
    private var anchorLon = Double.NaN
    private var grid: Map<Long, List<Camera>> = emptyMap()
    private var valid = false

    /** Number of database loads; exposed for tests and battery reports. */
    var loadCount: Int = 0
        private set

    val cachedCameraCount: Int get() = grid.values.sumOf { it.size }

    suspend fun candidatesNear(latitude: Double, longitude: Double, radiusM: Double): List<Camera> {
        require(radiusM <= config.cacheRadiusM - config.refreshAfterMovingM) {
            "Query radius $radiusM exceeds guaranteed cache coverage"
        }
        if (needsReload(latitude, longitude)) reload(latitude, longitude)
        val cells = GridCell.cellsCovering(latitude, longitude, radiusM)
        val result = ArrayList<Camera>()
        for (cell in cells) {
            val cameras = grid[cell] ?: continue
            for (camera in cameras) {
                if (GeoMath.distanceMeters(latitude, longitude, camera.latitude, camera.longitude) <= radiusM) {
                    result += camera
                }
            }
        }
        return result
    }

    /** Forget the cache, e.g. after a data sync changed camera records. */
    fun invalidate() {
        valid = false
    }

    fun clear() {
        grid = emptyMap()
        valid = false
        anchorLat = Double.NaN
        anchorLon = Double.NaN
    }

    private fun needsReload(latitude: Double, longitude: Double): Boolean {
        if (!valid || anchorLat.isNaN()) return true
        return GeoMath.distanceMeters(anchorLat, anchorLon, latitude, longitude) > config.refreshAfterMovingM
    }

    private suspend fun reload(latitude: Double, longitude: Double) {
        val cells = GridCell.cellsCovering(latitude, longitude, config.cacheRadiusM)
        val cameras = lookup.camerasInCells(cells)
        grid = cameras.groupBy { GridCell.cellId(it.latitude, it.longitude) }
        anchorLat = latitude
        anchorLon = longitude
        valid = true
        loadCount++
    }
}
