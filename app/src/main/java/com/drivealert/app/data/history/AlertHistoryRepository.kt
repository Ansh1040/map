package com.drivealert.app.data.history

import com.drivealert.app.database.AlertHistoryDao
import com.drivealert.app.database.AlertHistoryEntity
import com.drivealert.app.database.enumOrDefault
import com.drivealert.app.domain.model.CameraAlert
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.ports.AlertHistoryRecorder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AlertHistoryItem(
    val id: Long,
    val cameraId: String,
    val cameraType: CameraType,
    val roadName: String?,
    val city: String?,
    val speedLimitKmh: Int?,
    val distanceM: Int,
    val alertedAtMillis: Long,
)

/**
 * Local-only alert history. Nothing here is uploaded, and no driver
 * coordinates are stored.
 */
@Singleton
class AlertHistoryRepository @Inject constructor(
    private val dao: AlertHistoryDao,
) : AlertHistoryRecorder {

    override suspend fun record(alert: CameraAlert, sessionId: String) {
        dao.insert(
            AlertHistoryEntity(
                cameraId = alert.camera.id,
                cameraType = alert.camera.type.name,
                roadName = alert.camera.roadName,
                city = alert.camera.city,
                speedLimitKmh = alert.camera.speedLimitKmh,
                alertDistanceM = alert.distanceM,
                thresholdM = alert.thresholdM,
                alertedAt = alert.timestampMillis,
                sessionId = sessionId,
            ),
        )
    }

    fun observeHistory(limit: Int = 200): Flow<List<AlertHistoryItem>> =
        dao.observeRecent(limit).map { rows ->
            rows.map {
                AlertHistoryItem(
                    id = it.id,
                    cameraId = it.cameraId,
                    cameraType = enumOrDefault(it.cameraType, CameraType.FIXED_SPEED),
                    roadName = it.roadName,
                    city = it.city,
                    speedLimitKmh = it.speedLimitKmh,
                    distanceM = it.alertDistanceM,
                    alertedAtMillis = it.alertedAt,
                )
            }
        }

    suspend fun clear() = dao.deleteAll()

    suspend fun prune(retentionDays: Int, nowMillis: Long = System.currentTimeMillis()): Int =
        dao.deleteOlderThan(nowMillis - retentionDays * 24L * 3_600_000L)
}
