package com.drivealert.app.domain.geo

import kotlin.math.floor

/**
 * Fixed latitude/longitude grid used as a lightweight spatial index.
 *
 * Every camera row stores the id of the cell that contains it, so the database
 * can return candidates for a handful of cells (an indexed `IN` query) instead
 * of scanning the whole table. A 0.02 degree cell is about 2.2 km north-south
 * and 1.8-2.2 km east-west across India.
 */
object GridCell {
    const val CELL_SIZE_DEG = 0.02
    private const val COLUMNS = 18_000L // 360 / CELL_SIZE_DEG

    fun cellId(latitude: Double, longitude: Double): Long {
        val row = floor((latitude.coerceIn(-90.0, 90.0) + 90.0) / CELL_SIZE_DEG).toLong()
        val col = floor((longitude.coerceIn(-180.0, 180.0) + 180.0) / CELL_SIZE_DEG).toLong()
            .coerceIn(0L, COLUMNS - 1)
        return row * COLUMNS + col
    }

    /** All cell ids intersecting the square that encloses a circle of [radiusM]. */
    fun cellsCovering(latitude: Double, longitude: Double, radiusM: Double): List<Long> {
        val box = GeoMath.boundingBox(latitude, longitude, radiusM)
        return cellsCovering(box)
    }

    fun cellsCovering(box: BoundingBox): List<Long> {
        val minRow = floor((box.minLatitude + 90.0) / CELL_SIZE_DEG).toLong()
        val maxRow = floor((box.maxLatitude + 90.0) / CELL_SIZE_DEG).toLong()
        val minCol = floor((box.minLongitude + 180.0) / CELL_SIZE_DEG).toLong().coerceIn(0L, COLUMNS - 1)
        val maxCol = floor((box.maxLongitude + 180.0) / CELL_SIZE_DEG).toLong().coerceIn(0L, COLUMNS - 1)
        val cells = ArrayList<Long>(((maxRow - minRow + 1) * (maxCol - minCol + 1)).toInt())
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                cells += row * COLUMNS + col
            }
        }
        return cells
    }
}
