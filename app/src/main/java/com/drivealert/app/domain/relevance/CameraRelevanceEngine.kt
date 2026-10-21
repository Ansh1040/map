package com.drivealert.app.domain.relevance

import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.geo.RelativePosition
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.Heading
import com.drivealert.app.domain.model.HeadingQuality
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.model.RoadMatch
import com.drivealert.app.domain.model.VerificationStatus
import kotlin.math.abs

data class RelevanceConfig(
    /** Cameras farther than this are ignored entirely. */
    val searchRadiusM: Double = 5_000.0,
    /** Fixes worse than this cannot trigger alerts. */
    val maxUsableAccuracyM: Float = 100f,
    val minAlertConfidence: Double = 0.55,
    val baseLateralToleranceM: Double = 25.0,
    val tightLateralToleranceM: Double = 15.0,
    /** Lateral tolerance grows with distance to allow for road curvature. */
    val curvatureAllowance: Double = 0.15,
    /** Lateral offset is only trusted to reject a camera this close to it. */
    val lateralRejectDistanceM: Double = 150.0,
    /**
     * Cameras flagged as service lane / flyover / underpass / parallel road
     * are only alerted inside this distance (where lateral offset is
     * measurable) unless map matching confirms the road.
     */
    val ambiguousContextMaxAlertDistanceM: Double = 400.0,
    val ambiguousContextMaxAccuracyM: Float = 20f,
    /** Camera elevation must be WGS84 ellipsoidal height to be comparable with GNSS altitude. */
    val elevationToleranceM: Double = 10.0,
    val maxVerticalAccuracyForElevationM: Float = 6f,
    /** Without a heading, a camera this close still justifies precise tracking. */
    val approachCandidateWithoutHeadingM: Double = 1_500.0,
)

enum class RejectReason {
    NOT_VERIFIED,
    NO_HEADING,
    POOR_GPS,
    BEHIND,
    NOT_AHEAD,
    OPPOSITE_DIRECTION,
    CROSSING_ROAD,
    DIFFERENT_ROAD,
    LOW_CONFIDENCE,
}

data class CameraEvaluation(
    val camera: Camera,
    val distanceM: Double,
    val position: RelativePosition?,
    val ahead: AheadState?,
    val directionMatch: DirectionMatch,
    val confidence: Double,
    /** Lenient: worth switching to precise tracking for this camera. */
    val isApproachCandidate: Boolean,
    /** Strict: the camera is relevant enough to warn the driver now. */
    val isAlertable: Boolean,
    val rejectReason: RejectReason?,
)

/**
 * Decides which nearby cameras are relevant to the driver.
 *
 * Pipeline per camera: usable record -> within search radius -> verification
 * policy -> direction match (opposite carriageway / crossing road rejected) ->
 * map-matching (if available) -> ahead vs behind -> lateral/road-context checks
 * -> confidence score.
 */
class CameraRelevanceEngine(
    private val config: RelevanceConfig = RelevanceConfig(),
    private val bearingMatcher: BearingMatcher = BearingMatcher(),
) {
    val searchRadiusM: Double get() = config.searchRadiusM

    fun evaluate(
        fix: LocationFix,
        heading: Heading?,
        candidates: List<Camera>,
        nowMillis: Long,
        includeUnverified: Boolean,
        roadMatch: RoadMatch? = null,
    ): List<CameraEvaluation> {
        val result = ArrayList<CameraEvaluation>(candidates.size)
        for (camera in candidates) {
            if (!camera.isUsable(nowMillis)) continue
            val distance = GeoMath.distanceMeters(fix.latitude, fix.longitude, camera.latitude, camera.longitude)
            if (distance > config.searchRadiusM) continue
            result += evaluateCamera(fix, heading, camera, distance, includeUnverified, roadMatch)
        }
        result.sortBy { it.distanceM }
        return result
    }

    private fun evaluateCamera(
        fix: LocationFix,
        heading: Heading?,
        camera: Camera,
        distance: Double,
        includeUnverified: Boolean,
        roadMatch: RoadMatch?,
    ): CameraEvaluation {
        val statusAllowed = camera.verificationStatus.isTrusted ||
            (includeUnverified && camera.verificationStatus != VerificationStatus.REJECTED)

        if (heading == null) {
            return CameraEvaluation(
                camera = camera,
                distanceM = distance,
                position = null,
                ahead = null,
                directionMatch = DirectionMatch.UNKNOWN,
                confidence = 0.0,
                isApproachCandidate = statusAllowed && distance <= config.approachCandidateWithoutHeadingM,
                isAlertable = false,
                rejectReason = RejectReason.NO_HEADING,
            )
        }

        val position = GeoMath.relativePosition(
            fix.latitude, fix.longitude, heading.bearingDeg, camera.latitude, camera.longitude,
        )
        val direction = bearingMatcher.matchDirection(heading.bearingDeg, heading.quality, camera)
        val road = roadDecision(camera, roadMatch)
        val ambiguous = camera.roadContext.isAmbiguous && road != RoadDecision.SAME_SEGMENT
        val lateralTolerance = lateralToleranceM(position.distanceM, fix.accuracyM, ambiguous)
        val ahead = bearingMatcher.classifyAhead(position, fix.accuracyM, lateralTolerance)
        val roughlyAhead = bearingMatcher.isRoughlyAhead(position)

        fun reject(reason: RejectReason, candidate: Boolean = false, confidence: Double = 0.0) = CameraEvaluation(
            camera = camera,
            distanceM = distance,
            position = position,
            ahead = ahead,
            directionMatch = direction,
            confidence = confidence,
            isApproachCandidate = candidate,
            isAlertable = false,
            rejectReason = reason,
        )

        // Hard rejections: data says this camera does not apply to our direction/road.
        if (direction == DirectionMatch.OPPOSITE) return reject(RejectReason.OPPOSITE_DIRECTION)
        if (direction == DirectionMatch.CROSSING) return reject(RejectReason.CROSSING_ROAD)
        if (road == RoadDecision.DIFFERENT_SEGMENT) return reject(RejectReason.DIFFERENT_ROAD)
        if (ahead == AheadState.BEHIND) return reject(RejectReason.BEHIND)

        val candidate = statusAllowed && roughlyAhead
        if (!statusAllowed) return reject(RejectReason.NOT_VERIFIED)
        if (fix.accuracyM > config.maxUsableAccuracyM) return reject(RejectReason.POOR_GPS, candidate)
        if (ahead != AheadState.AHEAD) return reject(RejectReason.NOT_AHEAD, candidate)

        if (ambiguous) {
            // Parallel structures: only trust a close, precise, laterally aligned approach.
            if (fix.accuracyM > config.ambiguousContextMaxAccuracyM) return reject(RejectReason.POOR_GPS, candidate)
            if (position.distanceM > config.ambiguousContextMaxAlertDistanceM) {
                return reject(RejectReason.LOW_CONFIDENCE, candidate)
            }
            if (abs(position.crossTrackM) > lateralTolerance) return reject(RejectReason.DIFFERENT_ROAD, candidate)
        } else if (position.distanceM <= config.lateralRejectDistanceM && abs(position.crossTrackM) > lateralTolerance) {
            return reject(RejectReason.DIFFERENT_ROAD, candidate)
        }

        val confidence = confidence(fix, heading, camera, position, direction, road, ambiguous, lateralTolerance)
        if (confidence < config.minAlertConfidence) return reject(RejectReason.LOW_CONFIDENCE, candidate, confidence)

        return CameraEvaluation(
            camera = camera,
            distanceM = distance,
            position = position,
            ahead = ahead,
            directionMatch = direction,
            confidence = confidence,
            isApproachCandidate = true,
            isAlertable = true,
            rejectReason = null,
        )
    }

    fun lateralToleranceM(distanceM: Double, accuracyM: Float, ambiguous: Boolean): Double {
        val base = if (ambiguous) config.tightLateralToleranceM else config.baseLateralToleranceM
        val curvature = if (ambiguous) 0.0 else distanceM * config.curvatureAllowance
        return base + accuracyM + curvature
    }

    private fun confidence(
        fix: LocationFix,
        heading: Heading,
        camera: Camera,
        position: RelativePosition,
        direction: DirectionMatch,
        road: RoadDecision,
        ambiguous: Boolean,
        lateralTolerance: Double,
    ): Double {
        val headingScore = when (heading.quality) {
            HeadingQuality.HIGH -> 1.0
            HeadingQuality.MEDIUM -> 0.75
            HeadingQuality.LOW -> 0.45
        }
        val directionScore = when (direction) {
            DirectionMatch.MATCH -> 1.0
            DirectionMatch.UNKNOWN -> 0.5
            DirectionMatch.OPPOSITE, DirectionMatch.CROSSING -> 0.0
        }
        val angleScore = 1.0 - (abs(position.relativeAngleDeg) / bearingMatcher.strictConeDeg(position.distanceM)).coerceIn(0.0, 1.0)
        val laneScore = 1.0 - (abs(position.crossTrackM) / lateralTolerance).coerceIn(0.0, 1.0)
        val geometryScore = maxOf(angleScore, laneScore)
        val gpsScore = when {
            fix.accuracyM <= 10f -> 1.0
            fix.accuracyM <= 25f -> 0.8
            fix.accuracyM <= 50f -> 0.5
            else -> 0.2
        }

        var score = 0.30 * camera.confidenceScore.coerceIn(0.0, 1.0) +
            0.20 * headingScore +
            0.20 * directionScore +
            0.20 * geometryScore +
            0.10 * gpsScore

        if (ambiguous) score -= 0.10
        when (road) {
            RoadDecision.SAME_SEGMENT -> score += 0.15
            RoadDecision.NAME_MATCH -> score += 0.05
            RoadDecision.NAME_MISMATCH -> score -= 0.20
            RoadDecision.DIFFERENT_SEGMENT, RoadDecision.UNKNOWN -> Unit
        }
        if (isElevationMismatch(fix, camera)) score -= 0.35
        if (!camera.verificationStatus.isTrusted) score -= 0.10
        return score.coerceIn(0.0, 1.0)
    }

    private fun isElevationMismatch(fix: LocationFix, camera: Camera): Boolean {
        val cameraElevation = camera.elevationM ?: return false
        val altitude = fix.altitudeM ?: return false
        val vertical = fix.verticalAccuracyM ?: return false
        if (vertical > config.maxVerticalAccuracyForElevationM) return false
        return abs(altitude - cameraElevation) > config.elevationToleranceM + vertical
    }

    private fun roadDecision(camera: Camera, match: RoadMatch?): RoadDecision {
        if (match == null) return RoadDecision.UNKNOWN
        val segment = match.segmentId
        val cameraSegment = camera.roadSegmentId
        if (segment != null && cameraSegment != null) {
            return if (segment == cameraSegment) RoadDecision.SAME_SEGMENT else RoadDecision.DIFFERENT_SEGMENT
        }
        val name = match.roadName?.normalizedRoadName()
        val cameraName = camera.roadName?.normalizedRoadName()
        if (!name.isNullOrEmpty() && !cameraName.isNullOrEmpty()) {
            return if (name == cameraName) RoadDecision.NAME_MATCH else RoadDecision.NAME_MISMATCH
        }
        return RoadDecision.UNKNOWN
    }

    private fun String.normalizedRoadName(): String = lowercase().filter { it.isLetterOrDigit() }

    private enum class RoadDecision { SAME_SEGMENT, DIFFERENT_SEGMENT, NAME_MATCH, NAME_MISMATCH, UNKNOWN }
}
