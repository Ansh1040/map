package com.drivealert.app.domain.reporting

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.model.VerificationStatus
import kotlin.math.exp
import kotlin.math.min

enum class ReportType {
    NEW_CAMERA,
    CAMERA_REMOVED,
    INCORRECT_POSITION,
    INCORRECT_DIRECTION,
    INCORRECT_SPEED_LIMIT,
    TEMPORARY_MOBILE_CAMERA,
    DUPLICATE_CAMERA,

    /** Flag a community record or report as spam/abuse. */
    ABUSE;

    /** Report types that describe a location rather than an existing camera. */
    val needsLocation: Boolean get() = this == NEW_CAMERA || this == TEMPORARY_MOBILE_CAMERA || this == INCORRECT_POSITION

    val needsExistingCamera: Boolean
        get() = this != NEW_CAMERA && this != TEMPORARY_MOBILE_CAMERA
}

enum class ReportStatus {
    /** Saved on the device, waiting for upload. */
    PENDING_UPLOAD,
    SUBMITTED,
    UNDER_REVIEW,
    ACCEPTED,
    REJECTED,
    DUPLICATE,
    EXPIRED,
}

data class ReportDraft(
    val type: ReportType,
    val cameraId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val bearingDeg: Double? = null,
    val speedLimitKmh: Int? = null,
    val cameraType: CameraType? = null,
    val note: String? = null,
)

/** A report previously made from this device. */
data class PriorReport(
    val type: ReportType,
    val cameraId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val createdAtMillis: Long,
)

sealed interface ReportCheck {
    data class Accepted(val expiresAtMillis: Long?) : ReportCheck
    data class RateLimited(val retryAfterMs: Long) : ReportCheck
    data class Duplicate(val existingCameraId: String?, val reason: DuplicateReason) : ReportCheck
    data class Invalid(val reason: InvalidReason) : ReportCheck
}

enum class DuplicateReason { SAME_REPORT_RECENTLY, CAMERA_ALREADY_KNOWN, NEAR_OWN_RECENT_REPORT }

enum class InvalidReason { MISSING_LOCATION, MISSING_CAMERA, INVALID_SPEED_LIMIT, NOTE_TOO_LONG, INVALID_COORDINATES }

/** Signals the moderation backend aggregates for one camera record. */
data class VerificationSignals(
    val source: DataSource,
    val moderatorApproved: Boolean,
    val distinctConfirmations: Int,
    val disputes: Int,
    /** Mean reputation of confirming reporters, in [0, 1]. */
    val reporterReputation: Double,
    val daysSinceLastVerified: Double,
    val abuseFlags: Int,
)

data class ModerationConfig(
    val minIntervalBetweenReportsMs: Long = 30_000L,
    val maxReportsPerHour: Int = 5,
    val maxReportsPerDay: Int = 20,
    val duplicateWindowMs: Long = 24 * 3_600_000L,
    val duplicateRadiusM: Double = 40.0,
    val temporaryReportLifetimeMs: Long = 4 * 3_600_000L,
    val pendingReportLifetimeMs: Long = 30L * 24 * 3_600_000L,
    val maxNoteLength: Int = 500,
    /** Distinct confirmations needed before moderators can promote a community camera. */
    val minVerificationThreshold: Int = 3,
    /** Confidence halves after this many days without re-verification. */
    val confidenceHalfLifeDays: Double = 365.0,
    val abuseFlagsToHide: Int = 3,
)

/**
 * Rules for crowdsourced reports. The client applies [checkSubmission] before
 * queuing a report; [confidenceScore] and [statusFor] document the scoring the
 * moderation backend must implement (the backend is authoritative).
 *
 * Crowdsourced data never becomes trusted automatically: COMMUNITY_CONFIRMED
 * requires both the verification threshold and an explicit moderator approval.
 */
class ReportModerationPolicy(private val config: ModerationConfig = ModerationConfig()) {

    fun checkSubmission(
        draft: ReportDraft,
        priorReports: List<PriorReport>,
        nearbyCameras: List<Camera>,
        nowMillis: Long,
    ): ReportCheck {
        validate(draft)?.let { return ReportCheck.Invalid(it) }
        rateLimit(priorReports, nowMillis)?.let { return ReportCheck.RateLimited(it) }
        duplicate(draft, priorReports, nearbyCameras, nowMillis)?.let { return it }
        return ReportCheck.Accepted(expiresAtMillis = expiryFor(draft.type, nowMillis))
    }

    fun expiryFor(type: ReportType, createdAtMillis: Long): Long =
        if (type == ReportType.TEMPORARY_MOBILE_CAMERA) {
            createdAtMillis + config.temporaryReportLifetimeMs
        } else {
            createdAtMillis + config.pendingReportLifetimeMs
        }

    private fun validate(draft: ReportDraft): InvalidReason? {
        if (draft.type.needsLocation && (draft.latitude == null || draft.longitude == null)) {
            return InvalidReason.MISSING_LOCATION
        }
        if (draft.type.needsExistingCamera && draft.cameraId.isNullOrBlank()) return InvalidReason.MISSING_CAMERA
        val lat = draft.latitude
        val lon = draft.longitude
        if (lat != null && lon != null && (lat !in -90.0..90.0 || lon !in -180.0..180.0)) {
            return InvalidReason.INVALID_COORDINATES
        }
        draft.speedLimitKmh?.let { limit ->
            if (limit !in 10..150 || limit % 5 != 0) return InvalidReason.INVALID_SPEED_LIMIT
        }
        if (draft.type == ReportType.INCORRECT_SPEED_LIMIT && draft.speedLimitKmh == null) {
            return InvalidReason.INVALID_SPEED_LIMIT
        }
        if ((draft.note?.length ?: 0) > config.maxNoteLength) return InvalidReason.NOTE_TOO_LONG
        return null
    }

    private fun rateLimit(prior: List<PriorReport>, now: Long): Long? {
        val latest = prior.maxOfOrNull { it.createdAtMillis }
        if (latest != null && now - latest < config.minIntervalBetweenReportsMs) {
            return config.minIntervalBetweenReportsMs - (now - latest)
        }
        val lastHour = prior.filter { now - it.createdAtMillis < 3_600_000L }
        if (lastHour.size >= config.maxReportsPerHour) {
            return 3_600_000L - (now - lastHour.minOf { it.createdAtMillis })
        }
        val lastDay = prior.filter { now - it.createdAtMillis < 24 * 3_600_000L }
        if (lastDay.size >= config.maxReportsPerDay) {
            return 24 * 3_600_000L - (now - lastDay.minOf { it.createdAtMillis })
        }
        return null
    }

    private fun duplicate(
        draft: ReportDraft,
        prior: List<PriorReport>,
        nearbyCameras: List<Camera>,
        now: Long,
    ): ReportCheck.Duplicate? {
        val recent = prior.filter { now - it.createdAtMillis < config.duplicateWindowMs }
        if (draft.cameraId != null &&
            recent.any { it.type == draft.type && it.cameraId == draft.cameraId }
        ) {
            return ReportCheck.Duplicate(draft.cameraId, DuplicateReason.SAME_REPORT_RECENTLY)
        }
        val lat = draft.latitude
        val lon = draft.longitude
        if (lat != null && lon != null &&
            (draft.type == ReportType.NEW_CAMERA || draft.type == ReportType.TEMPORARY_MOBILE_CAMERA)
        ) {
            val existing = nearbyCameras.firstOrNull { camera ->
                camera.isActive && camera.verificationStatus != VerificationStatus.REJECTED &&
                    GeoMath.distanceMeters(lat, lon, camera.latitude, camera.longitude) <= config.duplicateRadiusM
            }
            if (existing != null) return ReportCheck.Duplicate(existing.id, DuplicateReason.CAMERA_ALREADY_KNOWN)
            val ownNearby = recent.any { report ->
                report.latitude != null && report.longitude != null && report.type == draft.type &&
                    GeoMath.distanceMeters(lat, lon, report.latitude, report.longitude) <= config.duplicateRadiusM
            }
            if (ownNearby) return ReportCheck.Duplicate(null, DuplicateReason.NEAR_OWN_RECENT_REPORT)
        }
        return null
    }

    /** Reference confidence score in [0, 1] for a camera record. */
    fun confidenceScore(signals: VerificationSignals): Double {
        val base = when (signals.source) {
            DataSource.OFFICIAL_GOVERNMENT -> 0.95
            DataSource.LICENSED_PROVIDER -> 0.90
            DataSource.ADMINISTRATOR -> 0.85
            DataSource.COMMUNITY_MODERATED -> {
                val confirmations = min(signals.distinctConfirmations, 6)
                0.30 + 0.08 * confirmations + 0.15 * signals.reporterReputation.coerceIn(0.0, 1.0) +
                    if (signals.moderatorApproved) 0.10 else 0.0
            }
            DataSource.TEST_ONLY_SYNTHETIC -> 0.50
        }
        val disputePenalty = 0.12 * signals.disputes
        val abusePenalty = 0.20 * signals.abuseFlags
        val decay = exp(-signals.daysSinceLastVerified.coerceAtLeast(0.0) * kotlin.math.ln(2.0) / config.confidenceHalfLifeDays)
        return ((base - disputePenalty - abusePenalty) * decay).coerceIn(0.0, 1.0)
    }

    /** Reference verification status for a camera record. */
    fun statusFor(signals: VerificationSignals): VerificationStatus {
        if (signals.abuseFlags >= config.abuseFlagsToHide && !signals.moderatorApproved) return VerificationStatus.REJECTED
        if (signals.disputes > 0 && signals.disputes >= signals.distinctConfirmations) return VerificationStatus.DISPUTED
        return when (signals.source) {
            DataSource.OFFICIAL_GOVERNMENT, DataSource.LICENSED_PROVIDER, DataSource.ADMINISTRATOR ->
                if (signals.moderatorApproved) VerificationStatus.VERIFIED else VerificationStatus.PENDING_VERIFICATION
            DataSource.COMMUNITY_MODERATED ->
                if (signals.moderatorApproved && signals.distinctConfirmations >= config.minVerificationThreshold) {
                    VerificationStatus.COMMUNITY_CONFIRMED
                } else {
                    VerificationStatus.PENDING_VERIFICATION
                }
            DataSource.TEST_ONLY_SYNTHETIC -> VerificationStatus.PENDING_VERIFICATION
        }
    }
}
