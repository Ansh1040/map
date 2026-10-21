package com.drivealert.app.database

import com.drivealert.app.domain.geo.GridCell
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.model.Directionality
import com.drivealert.app.domain.model.RoadContext
import com.drivealert.app.domain.model.VerificationStatus

inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

fun CameraEntity.toDomain(): Camera = Camera(
    id = id,
    latitude = latitude,
    longitude = longitude,
    type = enumOrDefault(type, CameraType.FIXED_SPEED),
    roadName = roadName,
    city = city,
    state = state,
    bearingDeg = bearingDeg,
    directionality = enumOrDefault(directionality, Directionality.UNKNOWN),
    speedLimitKmh = speedLimitKmh,
    verificationStatus = enumOrDefault(verificationStatus, VerificationStatus.PENDING_VERIFICATION),
    verificationCount = verificationCount,
    dataSource = enumOrDefault(dataSource, DataSource.COMMUNITY_MODERATED),
    sourceReference = sourceReference,
    lastVerifiedAtMillis = lastVerifiedAt,
    confidenceScore = confidenceScore,
    expiresAtMillis = expiresAt,
    isActive = isActive,
    roadContext = enumOrDefault(roadContext, RoadContext.UNKNOWN),
    roadSegmentId = roadSegmentId,
    elevationM = elevationM,
    packageId = packageId,
    isTestData = isTestData,
)

fun Camera.toEntity(updatedAtMillis: Long): CameraEntity = CameraEntity(
    id = id,
    latitude = latitude,
    longitude = longitude,
    cellId = GridCell.cellId(latitude, longitude),
    type = type.name,
    roadName = roadName,
    city = city,
    state = state,
    bearingDeg = bearingDeg,
    directionality = directionality.name,
    speedLimitKmh = speedLimitKmh,
    verificationStatus = verificationStatus.name,
    verificationCount = verificationCount,
    dataSource = dataSource.name,
    sourceReference = sourceReference,
    lastVerifiedAt = lastVerifiedAtMillis,
    confidenceScore = confidenceScore,
    expiresAt = expiresAtMillis,
    isActive = isActive,
    roadContext = roadContext.name,
    roadSegmentId = roadSegmentId,
    elevationM = elevationM,
    packageId = packageId,
    isTestData = isTestData,
    updatedAt = updatedAtMillis,
)
