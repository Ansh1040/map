package com.drivealert.app.domain.cameras

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.Camera

/** A camera seen from the user's position. */
data class NearbyCamera(
    val camera: Camera,
    val distanceM: Double,
    /** Compass bearing from the user to the camera, degrees [0, 360). */
    val bearingFromUserDeg: Double,
)

object NearbyCameras {
    const val DEFAULT_RADIUS_M = 25_000.0
    const val DEFAULT_LIMIT = 10

    /**
     * Usable cameras around ([latitude], [longitude]), nearest first, with distance and
     * direction from the user. Unverified reports are included only when [includeUnverified].
     */
    fun rank(
        latitude: Double,
        longitude: Double,
        cameras: List<Camera>,
        nowMillis: Long,
        includeUnverified: Boolean,
        limit: Int = DEFAULT_LIMIT,
        maxDistanceM: Double = DEFAULT_RADIUS_M,
    ): List<NearbyCamera> = cameras.asSequence()
        .filter { it.isUsable(nowMillis) && (includeUnverified || it.verificationStatus.isTrusted) }
        .map { camera ->
            NearbyCamera(
                camera = camera,
                distanceM = GeoMath.distanceMeters(latitude, longitude, camera.latitude, camera.longitude),
                bearingFromUserDeg = GeoMath.initialBearingDeg(latitude, longitude, camera.latitude, camera.longitude),
            )
        }
        .filter { it.distanceM <= maxDistanceM }
        .sortedBy { it.distanceM }
        .take(limit)
        .toList()

    /** 8-point compass sector for a bearing: 0 = north, 1 = north-east, … 7 = north-west. */
    fun compassSector(bearingDeg: Double): Int = (((GeoMath.normalizeBearing(bearingDeg) + 22.5) % 360.0) / 45.0).toInt()
}
