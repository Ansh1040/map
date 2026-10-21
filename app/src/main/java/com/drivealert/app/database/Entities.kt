package com.drivealert.app.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Downloaded camera record. Enums are stored by name so the schema stays
 * readable and stable across app versions.
 */
@Entity(
    tableName = "cameras",
    indices = [
        Index("cell_id"),
        Index("package_id"),
        Index(value = ["latitude", "longitude"]),
    ],
)
data class CameraEntity(
    @PrimaryKey val id: String,
    val latitude: Double,
    val longitude: Double,
    /** Spatial index cell, see GridCell. */
    @ColumnInfo(name = "cell_id") val cellId: Long,
    val type: String,
    @ColumnInfo(name = "road_name") val roadName: String?,
    val city: String?,
    val state: String?,
    @ColumnInfo(name = "bearing_deg") val bearingDeg: Double?,
    val directionality: String,
    @ColumnInfo(name = "speed_limit_kmh") val speedLimitKmh: Int?,
    @ColumnInfo(name = "verification_status") val verificationStatus: String,
    @ColumnInfo(name = "verification_count") val verificationCount: Int,
    @ColumnInfo(name = "data_source") val dataSource: String,
    @ColumnInfo(name = "source_reference") val sourceReference: String?,
    @ColumnInfo(name = "last_verified_at") val lastVerifiedAt: Long?,
    @ColumnInfo(name = "confidence_score") val confidenceScore: Double,
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
    @ColumnInfo(name = "road_context") val roadContext: String,
    @ColumnInfo(name = "road_segment_id") val roadSegmentId: String?,
    @ColumnInfo(name = "elevation_m") val elevationM: Double?,
    @ColumnInfo(name = "package_id") val packageId: String,
    @ColumnInfo(name = "is_test_data") val isTestData: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** A city/state camera data package and its sync metadata. */
@Entity(tableName = "data_packages")
data class DataPackageEntity(
    @PrimaryKey val id: String,
    val name: String,
    val state: String,
    val city: String?,
    /** Server data version applied locally; incremental sync asks for changes after it. */
    @ColumnInfo(name = "data_version") val dataVersion: Long,
    @ColumnInfo(name = "camera_count") val cameraCount: Int,
    @ColumnInfo(name = "last_updated_at") val lastUpdatedAt: Long?,
    @ColumnInfo(name = "is_installed") val isInstalled: Boolean,
    @ColumnInfo(name = "coverage_note") val coverageNote: String?,
    @ColumnInfo(name = "is_test_data") val isTestData: Boolean,
)

/**
 * Local-only alert history. Deliberately stores no driver coordinates:
 * only which camera was announced and when.
 */
@Entity(tableName = "alert_history", indices = [Index("alerted_at")])
data class AlertHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "camera_id") val cameraId: String,
    @ColumnInfo(name = "camera_type") val cameraType: String,
    @ColumnInfo(name = "road_name") val roadName: String?,
    val city: String?,
    @ColumnInfo(name = "speed_limit_kmh") val speedLimitKmh: Int?,
    @ColumnInfo(name = "alert_distance_m") val alertDistanceM: Int,
    @ColumnInfo(name = "threshold_m") val thresholdM: Int,
    @ColumnInfo(name = "alerted_at") val alertedAt: Long,
    @ColumnInfo(name = "session_id") val sessionId: String,
)

/** A community report created on this device. */
@Entity(tableName = "camera_reports", indices = [Index("created_at"), Index("status")])
data class CameraReportEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "local_id") val localId: Long = 0,
    @ColumnInfo(name = "remote_id") val remoteId: String?,
    @ColumnInfo(name = "report_type") val reportType: String,
    @ColumnInfo(name = "camera_id") val cameraId: String?,
    val latitude: Double?,
    val longitude: Double?,
    @ColumnInfo(name = "bearing_deg") val bearingDeg: Double?,
    @ColumnInfo(name = "speed_limit_kmh") val speedLimitKmh: Int?,
    @ColumnInfo(name = "camera_type") val cameraType: String?,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val status: String,
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
    @ColumnInfo(name = "last_error") val lastError: String?,
)

/** Append-only audit trail for each report's lifecycle. */
@Entity(
    tableName = "report_audit",
    foreignKeys = [
        ForeignKey(
            entity = CameraReportEntity::class,
            parentColumns = ["local_id"],
            childColumns = ["report_local_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("report_local_id")],
)
data class ReportAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "report_local_id") val reportLocalId: Long,
    val timestamp: Long,
    val event: String,
    val detail: String?,
)
