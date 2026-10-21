package com.drivealert.app.domain.model

enum class CameraType {
    FIXED_SPEED,
    MOBILE_SPEED,
    RED_LIGHT,
    SPEED_AND_RED_LIGHT,
    ANPR,
    BUS_LANE,
    RESTRICTED_ZONE,
    USER_REPORTED;

    val enforcesSpeed: Boolean
        get() = this == FIXED_SPEED || this == MOBILE_SPEED || this == SPEED_AND_RED_LIGHT
}

enum class VerificationStatus {
    /** Confirmed by an official or licensed source, or by a moderator. */
    VERIFIED,

    /** Crowdsourced record that passed moderator review and the verification threshold. */
    COMMUNITY_CONFIRMED,

    /** Reported but not yet reviewed. Never alerts unless the user opts in. */
    PENDING_VERIFICATION,

    /** Conflicting reports; shown with low confidence. */
    DISPUTED,

    /** Rejected by moderation. Never shown or alerted. */
    REJECTED;

    val isTrusted: Boolean get() = this == VERIFIED || this == COMMUNITY_CONFIRMED
}

/** Which traffic direction(s) the camera enforces. */
enum class Directionality {
    /** Enforces traffic travelling on [Camera.bearingDeg]. */
    ONE_DIRECTION,

    /** Enforces both [Camera.bearingDeg] and the reverse direction. */
    BOTH_DIRECTIONS,

    /** Monitored direction not known. Alerts use lower confidence. */
    UNKNOWN,
}

/**
 * Road geometry hint stored with a camera. Used to suppress likely false alerts
 * on parallel carriageways, service lanes, flyovers and underpasses when no
 * map-matching information is available.
 */
enum class RoadContext {
    MAIN_CARRIAGEWAY,
    SERVICE_LANE,
    FLYOVER,
    UNDERPASS,
    PARALLEL_ROAD_NEARBY,
    JUNCTION,
    UNKNOWN;

    val isAmbiguous: Boolean
        get() = this == SERVICE_LANE || this == FLYOVER || this == UNDERPASS || this == PARALLEL_ROAD_NEARBY
}

/**
 * Where a camera record came from. Only legally reusable sources are accepted
 * by the sync layer; see `CameraRecordValidator`.
 */
enum class DataSource {
    OFFICIAL_GOVERNMENT,
    LICENSED_PROVIDER,
    ADMINISTRATOR,
    COMMUNITY_MODERATED,

    /**
     * OpenStreetMap open data (ODbL 1.0, "© OpenStreetMap contributors").
     * Community-maintained and publicly reviewed; not official.
     */
    OPENSTREETMAP,

    /** Synthetic records used only by tests and debug builds. Never real locations. */
    TEST_ONLY_SYNTHETIC,
}

data class Camera(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val type: CameraType,
    val roadName: String? = null,
    val city: String? = null,
    val state: String? = null,
    /** Travel direction monitored by the camera, degrees clockwise from north. */
    val bearingDeg: Double? = null,
    val directionality: Directionality = Directionality.UNKNOWN,
    val speedLimitKmh: Int? = null,
    val verificationStatus: VerificationStatus = VerificationStatus.VERIFIED,
    val verificationCount: Int = 0,
    val dataSource: DataSource = DataSource.OFFICIAL_GOVERNMENT,
    /** Licence or reference for the source record (required for synced data). */
    val sourceReference: String? = null,
    val lastVerifiedAtMillis: Long? = null,
    /** Data-quality confidence in [0, 1] supplied by the data pipeline. */
    val confidenceScore: Double = 0.9,
    /** Temporary/mobile locations stop alerting after this time. */
    val expiresAtMillis: Long? = null,
    val isActive: Boolean = true,
    val roadContext: RoadContext = RoadContext.UNKNOWN,
    /** Map-matching segment id when the data provider supplies one. */
    val roadSegmentId: String? = null,
    val elevationM: Double? = null,
    val packageId: String = "",
    val isTestData: Boolean = false,
) {
    fun isExpired(nowMillis: Long): Boolean = expiresAtMillis != null && nowMillis >= expiresAtMillis

    fun isUsable(nowMillis: Long): Boolean =
        isActive && !isExpired(nowMillis) && verificationStatus != VerificationStatus.REJECTED
}
