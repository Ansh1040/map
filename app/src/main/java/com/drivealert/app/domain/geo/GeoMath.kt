package com.drivealert.app.domain.geo

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val latitude: Double, val longitude: Double)

data class BoundingBox(
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
) {
    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in minLatitude..maxLatitude && longitude in minLongitude..maxLongitude
}

/**
 * Spherical-earth geometry helpers. Accuracy is well under 0.5% for the
 * sub-20 km distances used by camera detection, which is far below GPS error.
 */
object GeoMath {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Must match [EARTH_RADIUS_M] so boxes/cells always enclose haversine radii. */
    private const val METERS_PER_DEGREE_LAT = EARTH_RADIUS_M * Math.PI / 180.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** Initial great-circle bearing from point 1 to point 2, in degrees [0, 360). */
    fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return normalizeBearing(Math.toDegrees(atan2(y, x)))
    }

    fun normalizeBearing(degrees: Double): Double {
        val r = degrees % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Smallest absolute difference between two bearings, in [0, 180]. */
    fun angularDifferenceDeg(a: Double, b: Double): Double {
        val d = abs(normalizeBearing(a) - normalizeBearing(b))
        return if (d > 180.0) 360.0 - d else d
    }

    /** Signed rotation from [from] to [to], in (-180, 180]. Positive = clockwise. */
    fun signedAngleDeg(from: Double, to: Double): Double {
        var d = normalizeBearing(to) - normalizeBearing(from)
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Point reached by travelling [distanceM] from the origin on [bearingDeg]. */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): GeoPoint {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(phi2),
        )
        val lonDeg = (Math.toDegrees(lambda2) + 540.0) % 360.0 - 180.0
        return GeoPoint(Math.toDegrees(phi2), lonDeg)
    }

    /** Point offset by metres north/east; convenient for building test geometry. */
    fun offset(lat: Double, lon: Double, northM: Double, eastM: Double): GeoPoint {
        val dLat = northM / METERS_PER_DEGREE_LAT
        val dLon = eastM / (METERS_PER_DEGREE_LAT * max(cos(Math.toRadians(lat)), 1e-6))
        return GeoPoint(lat + dLat, lon + dLon)
    }

    fun boundingBox(lat: Double, lon: Double, radiusM: Double): BoundingBox {
        val dLat = radiusM / METERS_PER_DEGREE_LAT
        val dLon = radiusM / (METERS_PER_DEGREE_LAT * max(cos(Math.toRadians(lat)), 1e-6))
        return BoundingBox(
            minLatitude = (lat - dLat).coerceAtLeast(-90.0),
            maxLatitude = (lat + dLat).coerceAtMost(90.0),
            minLongitude = (lon - dLon).coerceAtLeast(-180.0),
            maxLongitude = (lon + dLon).coerceAtMost(180.0),
        )
    }

    /**
     * Position of a target relative to an observer moving on [headingDeg].
     * Along-track is positive ahead of the observer; cross-track is positive
     * to the right of the direction of travel.
     */
    fun relativePosition(
        fromLat: Double,
        fromLon: Double,
        headingDeg: Double,
        toLat: Double,
        toLon: Double,
    ): RelativePosition {
        val distance = distanceMeters(fromLat, fromLon, toLat, toLon)
        val bearing = initialBearingDeg(fromLat, fromLon, toLat, toLon)
        val relative = signedAngleDeg(headingDeg, bearing)
        val rad = Math.toRadians(relative)
        return RelativePosition(
            distanceM = distance,
            bearingToTargetDeg = bearing,
            relativeAngleDeg = relative,
            alongTrackM = distance * cos(rad),
            crossTrackM = distance * sin(rad),
        )
    }
}

data class RelativePosition(
    val distanceM: Double,
    val bearingToTargetDeg: Double,
    /** Signed angle between heading and the target, (-180, 180]. */
    val relativeAngleDeg: Double,
    val alongTrackM: Double,
    val crossTrackM: Double,
)
