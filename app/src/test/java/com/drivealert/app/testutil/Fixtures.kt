package com.drivealert.app.testutil

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.geo.GeoPoint
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.model.Directionality
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.model.RoadContext
import com.drivealert.app.domain.model.VerificationStatus

/**
 * Test geometry. The origin is an arbitrary point; every camera built here is
 * synthetic (DataSource.TEST_ONLY_SYNTHETIC) and does not describe a real camera.
 */
object Fixtures {
    const val ORIGIN_LAT = 12.9716
    const val ORIGIN_LON = 77.5946

    /** Point [aheadM] metres along [headingDeg] from the origin, offset [rightM] to the right. */
    fun point(aheadM: Double, rightM: Double = 0.0, headingDeg: Double = 0.0, fromLat: Double = ORIGIN_LAT, fromLon: Double = ORIGIN_LON): GeoPoint {
        val ahead = GeoMath.destination(fromLat, fromLon, headingDeg, aheadM)
        if (rightM == 0.0) return ahead
        return GeoMath.destination(ahead.latitude, ahead.longitude, GeoMath.normalizeBearing(headingDeg + 90.0), rightM)
    }

    fun fix(
        at: GeoPoint,
        elapsedMs: Long,
        accuracyM: Float = 5f,
        speedMps: Float? = 15f,
        bearingDeg: Float? = 0f,
        bearingAccuracyDeg: Float? = 5f,
    ): LocationFix = LocationFix(
        latitude = at.latitude,
        longitude = at.longitude,
        accuracyM = accuracyM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        bearingAccuracyDeg = bearingAccuracyDeg,
        timeMillis = 1_700_000_000_000L + elapsedMs,
        elapsedRealtimeMillis = elapsedMs,
    )

    fun camera(
        id: String,
        at: GeoPoint,
        type: CameraType = CameraType.FIXED_SPEED,
        bearingDeg: Double? = 0.0,
        directionality: Directionality = Directionality.ONE_DIRECTION,
        status: VerificationStatus = VerificationStatus.VERIFIED,
        speedLimitKmh: Int? = 60,
        roadContext: RoadContext = RoadContext.MAIN_CARRIAGEWAY,
        expiresAtMillis: Long? = null,
        isActive: Boolean = true,
        roadSegmentId: String? = null,
        elevationM: Double? = null,
        confidence: Double = 0.9,
    ): Camera = Camera(
        id = id,
        latitude = at.latitude,
        longitude = at.longitude,
        type = type,
        roadName = "Synthetic test road",
        city = "Test city",
        state = "Test state",
        bearingDeg = bearingDeg,
        directionality = directionality,
        speedLimitKmh = speedLimitKmh,
        verificationStatus = status,
        verificationCount = 3,
        dataSource = DataSource.TEST_ONLY_SYNTHETIC,
        sourceReference = "unit-test",
        lastVerifiedAtMillis = 0L,
        confidenceScore = confidence,
        expiresAtMillis = expiresAtMillis,
        isActive = isActive,
        roadContext = roadContext,
        roadSegmentId = roadSegmentId,
        elevationM = elevationM,
        packageId = "test",
        isTestData = true,
    )
}
