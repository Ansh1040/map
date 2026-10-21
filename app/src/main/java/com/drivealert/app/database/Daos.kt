package com.drivealert.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CameraDao {
    /** Spatial-index lookup: only rows in the requested grid cells are read. */
    @Query("SELECT * FROM cameras WHERE is_active = 1 AND cell_id IN (:cellIds)")
    suspend fun activeInCells(cellIds: List<Long>): List<CameraEntity>

    @Query(
        """
        SELECT * FROM cameras
        WHERE is_active = 1
          AND latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
        LIMIT :limit
        """,
    )
    suspend fun activeInBounds(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int): List<CameraEntity>

    @Query("SELECT * FROM cameras WHERE id = :id")
    fun observeById(id: String): Flow<CameraEntity?>

    @Query("SELECT * FROM cameras WHERE id = :id")
    suspend fun getById(id: String): CameraEntity?

    @Query(
        """
        SELECT * FROM cameras
        WHERE is_active = 1
          AND (road_name LIKE '%' || :query || '%' OR city LIKE '%' || :query || '%' OR state LIKE '%' || :query || '%')
        ORDER BY city, road_name
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, limit: Int): List<CameraEntity>

    @Query("SELECT COUNT(*) FROM cameras WHERE is_active = 1")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM cameras WHERE package_id = :packageId AND is_active = 1")
    suspend fun countInPackage(packageId: String): Int

    @Upsert
    suspend fun upsertAll(cameras: List<CameraEntity>)

    @Query("DELETE FROM cameras WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM cameras WHERE package_id = :packageId")
    suspend fun deleteByPackage(packageId: String)

    @Query("DELETE FROM cameras WHERE is_test_data = 1")
    suspend fun deleteTestData()

    @Query("DELETE FROM cameras WHERE expires_at IS NOT NULL AND expires_at < :nowMillis")
    suspend fun deleteExpired(nowMillis: Long): Int

    @Transaction
    suspend fun applyDelta(packageId: String, replaceAll: Boolean, upserts: List<CameraEntity>, removedIds: List<String>) {
        if (replaceAll) deleteByPackage(packageId)
        if (removedIds.isNotEmpty()) removedIds.chunked(500).forEach { deleteByIds(it) }
        if (upserts.isNotEmpty()) upserts.chunked(500).forEach { upsertAll(it) }
    }
}

@Dao
interface DataPackageDao {
    @Query("SELECT * FROM data_packages ORDER BY state, name")
    fun observeAll(): Flow<List<DataPackageEntity>>

    @Query("SELECT * FROM data_packages WHERE is_installed = 1")
    suspend fun installed(): List<DataPackageEntity>

    @Query("SELECT * FROM data_packages WHERE id = :id")
    suspend fun getById(id: String): DataPackageEntity?

    @Query("SELECT MAX(last_updated_at) FROM data_packages WHERE is_installed = 1")
    fun observeLastUpdated(): Flow<Long?>

    @Upsert
    suspend fun upsert(pkg: DataPackageEntity)

    @Upsert
    suspend fun upsertAll(packages: List<DataPackageEntity>)

    @Query("UPDATE data_packages SET is_installed = :installed WHERE id = :id")
    suspend fun setInstalled(id: String, installed: Boolean)

    @Query("DELETE FROM data_packages WHERE is_test_data = 1")
    suspend fun deleteTestData()
}

@Dao
interface AlertHistoryDao {
    @Insert
    suspend fun insert(entry: AlertHistoryEntity): Long

    @Query("SELECT * FROM alert_history ORDER BY alerted_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AlertHistoryEntity>>

    @Query("SELECT COUNT(*) FROM alert_history")
    suspend fun count(): Int

    @Query("DELETE FROM alert_history")
    suspend fun deleteAll()

    @Query("DELETE FROM alert_history WHERE alerted_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int
}

@Dao
interface ReportDao {
    @Insert
    suspend fun insert(report: CameraReportEntity): Long

    @Update
    suspend fun update(report: CameraReportEntity)

    @Insert
    suspend fun insertAudit(entry: ReportAuditEntity)

    @Query("SELECT * FROM camera_reports WHERE local_id = :localId")
    suspend fun getById(localId: Long): CameraReportEntity?

    @Query("SELECT * FROM camera_reports ORDER BY created_at DESC")
    fun observeAll(): Flow<List<CameraReportEntity>>

    @Query("SELECT * FROM camera_reports WHERE created_at >= :sinceMillis")
    suspend fun createdSince(sinceMillis: Long): List<CameraReportEntity>

    @Query("SELECT * FROM camera_reports WHERE status = 'PENDING_UPLOAD' ORDER BY created_at")
    suspend fun pendingUploads(): List<CameraReportEntity>

    @Query("SELECT * FROM report_audit WHERE report_local_id = :localId ORDER BY timestamp")
    suspend fun auditFor(localId: Long): List<ReportAuditEntity>

    @Query(
        """
        UPDATE camera_reports SET status = 'EXPIRED'
        WHERE expires_at IS NOT NULL AND expires_at < :nowMillis
          AND status IN ('PENDING_UPLOAD', 'SUBMITTED', 'UNDER_REVIEW')
        """,
    )
    suspend fun expireOld(nowMillis: Long): Int

    @Query("DELETE FROM camera_reports")
    suspend fun deleteAll()

    @Transaction
    suspend fun insertWithAudit(report: CameraReportEntity, event: String, detail: String?): Long {
        val id = insert(report)
        insertAudit(ReportAuditEntity(reportLocalId = id, timestamp = report.createdAt, event = event, detail = detail))
        return id
    }
}
