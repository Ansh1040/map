package com.drivealert.app.data.testdata

import com.drivealert.app.database.enumOrDefault
import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.model.Directionality
import com.drivealert.app.domain.model.RoadContext
import com.drivealert.app.domain.model.VerificationStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * TEST-ONLY synthetic dataset format.
 *
 * Positions are NOT real-world coordinates. Each entry is an offset (metres
 * ahead / to the right) and a bearing offset relative to an anchor position and
 * heading chosen at load time, so a tester can lay out a scenario on any road.
 * Every generated record is marked [Camera.isTestData] and
 * [DataSource.TEST_ONLY_SYNTHETIC], and alerts for it say so in the history.
 */
@Serializable
data class SyntheticDatasetFile(
    @SerialName("_warning") val warning: String,
    @SerialName("package_id") val packageId: String,
    val cameras: List<SyntheticCameraSpec>,
)

@Serializable
data class SyntheticCameraSpec(
    val id: String,
    val label: String,
    @SerialName("ahead_m") val aheadM: Double,
    @SerialName("right_m") val rightM: Double = 0.0,
    val type: String,
    /** Monitored direction relative to the anchor heading (0 = same direction as the tester). */
    @SerialName("bearing_offset_deg") val bearingOffsetDeg: Double? = null,
    val directionality: String = "ONE_DIRECTION",
    @SerialName("speed_limit_kmh") val speedLimitKmh: Int? = null,
    @SerialName("verification_status") val verificationStatus: String = "VERIFIED",
    @SerialName("road_context") val roadContext: String = "MAIN_CARRIAGEWAY",
)

object SyntheticCameraDataset {
    const val ASSET_NAME = "test_only_mock_cameras.json"
    const val TEST_PACKAGE_ID = "test-only-synthetic"
    const val ID_PREFIX = "TEST-"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): SyntheticDatasetFile {
        val file = json.decodeFromString(SyntheticDatasetFile.serializer(), text)
        require(file.warning.contains("TEST-ONLY")) { "Synthetic dataset must carry a TEST-ONLY warning" }
        require(file.cameras.all { it.id.startsWith(ID_PREFIX) }) { "Synthetic camera ids must start with $ID_PREFIX" }
        return file
    }

    /** Places the synthetic scenario around [anchorLat]/[anchorLon] facing [anchorHeadingDeg]. */
    fun materialize(
        file: SyntheticDatasetFile,
        anchorLat: Double,
        anchorLon: Double,
        anchorHeadingDeg: Double,
        nowMillis: Long,
    ): List<Camera> = file.cameras.map { spec ->
        val ahead = GeoMath.destination(anchorLat, anchorLon, anchorHeadingDeg, spec.aheadM)
        val point = if (spec.rightM == 0.0) {
            ahead
        } else {
            GeoMath.destination(ahead.latitude, ahead.longitude, GeoMath.normalizeBearing(anchorHeadingDeg + 90.0), spec.rightM)
        }
        Camera(
            id = spec.id,
            latitude = point.latitude,
            longitude = point.longitude,
            type = enumOrDefault(spec.type, CameraType.FIXED_SPEED),
            roadName = "TEST ONLY – ${spec.label}",
            city = "Synthetic test area",
            state = null,
            bearingDeg = spec.bearingOffsetDeg?.let { GeoMath.normalizeBearing(anchorHeadingDeg + it) },
            directionality = enumOrDefault(spec.directionality, Directionality.UNKNOWN),
            speedLimitKmh = spec.speedLimitKmh,
            verificationStatus = enumOrDefault(spec.verificationStatus, VerificationStatus.PENDING_VERIFICATION),
            verificationCount = 0,
            dataSource = DataSource.TEST_ONLY_SYNTHETIC,
            sourceReference = "Synthetic test fixture – not a real camera",
            lastVerifiedAtMillis = nowMillis,
            confidenceScore = 0.9,
            expiresAtMillis = null,
            isActive = true,
            roadContext = enumOrDefault(spec.roadContext, RoadContext.UNKNOWN),
            packageId = TEST_PACKAGE_ID,
            isTestData = true,
        )
    }
}

/** Debug-only helpers; the release implementation is a no-op. */
interface TestDataTools {
    val isAvailable: Boolean
    suspend fun loadSyntheticCamerasAround(latitude: Double, longitude: Double, headingDeg: Double): Int
    suspend fun clearSyntheticCameras()
}
