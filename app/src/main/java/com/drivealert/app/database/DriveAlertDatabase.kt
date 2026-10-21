package com.drivealert.app.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        CameraEntity::class,
        DataPackageEntity::class,
        AlertHistoryEntity::class,
        CameraReportEntity::class,
        ReportAuditEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DriveAlertDatabase : RoomDatabase() {
    abstract fun cameraDao(): CameraDao
    abstract fun dataPackageDao(): DataPackageDao
    abstract fun alertHistoryDao(): AlertHistoryDao
    abstract fun reportDao(): ReportDao

    companion object {
        const val NAME = "drive_alert.db"
    }
}
