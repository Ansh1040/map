package com.drivealert.app.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Contract for the camera-data backend. The server is NOT part of this
 * repository; see docs/server-api-contract.md. Configure its HTTPS base URL
 * with CAMERA_API_BASE_URL (app/build.gradle.kts).
 *
 * No endpoint receives the driver's location or trip history: packages are
 * requested by id, and only explicit user reports carry coordinates.
 */
interface CameraDataApi {
    @GET("v1/packages")
    suspend fun packages(): List<PackageDto>

    /** Changes after [sinceVersion]; the server may answer with a full replacement. */
    @GET("v1/packages/{id}/delta")
    suspend fun packageDelta(
        @Path("id") packageId: String,
        @Query("since") sinceVersion: Long,
    ): PackageDeltaDto

    @POST("v1/reports")
    suspend fun submitReport(@Body report: ReportRequestDto): ReportResponseDto
}

@Serializable
data class PackageDto(
    val id: String,
    val name: String,
    val state: String,
    val city: String? = null,
    @SerialName("data_version") val dataVersion: Long,
    @SerialName("camera_count") val cameraCount: Int,
    @SerialName("coverage_note") val coverageNote: String? = null,
)

@Serializable
data class PackageDeltaDto(
    @SerialName("package_id") val packageId: String,
    @SerialName("from_version") val fromVersion: Long,
    @SerialName("to_version") val toVersion: Long,
    @SerialName("replace_all") val replaceAll: Boolean = false,
    val upserts: List<CameraDto> = emptyList(),
    @SerialName("removed_ids") val removedIds: List<String> = emptyList(),
)

@Serializable
data class CameraDto(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val type: String,
    @SerialName("road_name") val roadName: String? = null,
    val city: String? = null,
    val state: String? = null,
    @SerialName("bearing_deg") val bearingDeg: Double? = null,
    val directionality: String = "UNKNOWN",
    @SerialName("speed_limit_kmh") val speedLimitKmh: Int? = null,
    @SerialName("verification_status") val verificationStatus: String,
    @SerialName("verification_count") val verificationCount: Int = 0,
    @SerialName("data_source") val dataSource: String,
    /** Licence / provenance reference. Records without one are rejected on device. */
    @SerialName("source_reference") val sourceReference: String? = null,
    @SerialName("last_verified_at") val lastVerifiedAt: Long? = null,
    @SerialName("confidence_score") val confidenceScore: Double = 0.5,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("road_context") val roadContext: String = "UNKNOWN",
    @SerialName("road_segment_id") val roadSegmentId: String? = null,
    /** WGS84 ellipsoidal height (same reference as GNSS altitude). */
    @SerialName("elevation_m") val elevationM: Double? = null,
)

@Serializable
data class ReportRequestDto(
    @SerialName("client_report_id") val clientReportId: String,
    val type: String,
    @SerialName("camera_id") val cameraId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("bearing_deg") val bearingDeg: Double? = null,
    @SerialName("speed_limit_kmh") val speedLimitKmh: Int? = null,
    @SerialName("camera_type") val cameraType: String? = null,
    val note: String? = null,
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class ReportResponseDto(
    @SerialName("report_id") val reportId: String,
    val status: String,
)
