package com.drivealert.app.network

import com.drivealert.app.database.enumOrDefault
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.model.Directionality
import com.drivealert.app.domain.model.RoadContext
import com.drivealert.app.domain.model.VerificationStatus

/**
 * Defensive validation of downloaded records. Anything that is malformed, or
 * lacks a lawful provenance reference, is dropped instead of being trusted.
 */
object CameraRecordValidator {
    private val ACCEPTED_SOURCES = setOf(
        DataSource.OFFICIAL_GOVERNMENT,
        DataSource.LICENSED_PROVIDER,
        DataSource.ADMINISTRATOR,
        DataSource.COMMUNITY_MODERATED,
    )

    fun toCameraOrNull(dto: CameraDto, packageId: String): Camera? {
        if (dto.id.isBlank()) return null
        if (dto.latitude !in -90.0..90.0 || dto.longitude !in -180.0..180.0) return null
        if (dto.latitude == 0.0 && dto.longitude == 0.0) return null
        val type = CameraType.entries.firstOrNull { it.name == dto.type } ?: return null
        val source = DataSource.entries.firstOrNull { it.name == dto.dataSource } ?: return null
        if (source !in ACCEPTED_SOURCES) return null
        if (dto.sourceReference.isNullOrBlank()) return null
        val status = VerificationStatus.entries.firstOrNull { it.name == dto.verificationStatus } ?: return null
        if (status == VerificationStatus.REJECTED) return null
        val bearing = dto.bearingDeg?.takeIf { it in 0.0..360.0 }
        val limit = dto.speedLimitKmh?.takeIf { it in 5..150 }
        return Camera(
            id = dto.id,
            latitude = dto.latitude,
            longitude = dto.longitude,
            type = type,
            roadName = dto.roadName?.take(120),
            city = dto.city?.take(80),
            state = dto.state?.take(80),
            bearingDeg = bearing,
            directionality = if (bearing == null) Directionality.UNKNOWN else enumOrDefault(dto.directionality, Directionality.UNKNOWN),
            speedLimitKmh = limit,
            verificationStatus = status,
            verificationCount = dto.verificationCount.coerceAtLeast(0),
            dataSource = source,
            sourceReference = dto.sourceReference.take(200),
            lastVerifiedAtMillis = dto.lastVerifiedAt,
            confidenceScore = dto.confidenceScore.coerceIn(0.0, 1.0),
            expiresAtMillis = dto.expiresAt,
            isActive = dto.isActive,
            roadContext = enumOrDefault(dto.roadContext, RoadContext.UNKNOWN),
            roadSegmentId = dto.roadSegmentId,
            elevationM = dto.elevationM,
            packageId = packageId,
            isTestData = false,
        )
    }
}
