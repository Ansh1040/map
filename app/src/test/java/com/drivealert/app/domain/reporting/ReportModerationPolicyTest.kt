package com.drivealert.app.domain.reporting

import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.model.VerificationStatus
import com.drivealert.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportModerationPolicyTest {
    private val policy = ReportModerationPolicy()
    private val now = 10L * 24 * 3_600_000L
    private val here = Fixtures.point(0.0)

    private fun newCamera(at: com.drivealert.app.domain.geo.GeoPoint = here) =
        ReportDraft(ReportType.NEW_CAMERA, latitude = at.latitude, longitude = at.longitude)

    private fun prior(type: ReportType, minutesAgo: Long, cameraId: String? = null, at: com.drivealert.app.domain.geo.GeoPoint? = null) =
        PriorReport(type, cameraId, at?.latitude, at?.longitude, now - minutesAgo * 60_000L)

    @Test
    fun `valid new camera report is accepted with a pending expiry`() {
        val check = policy.checkSubmission(newCamera(), emptyList(), emptyList(), now)
        assertTrue(check is ReportCheck.Accepted)
        assertEquals(now + 30L * 24 * 3_600_000L, (check as ReportCheck.Accepted).expiresAtMillis)
    }

    @Test
    fun `temporary mobile camera reports expire after four hours`() {
        val draft = ReportDraft(ReportType.TEMPORARY_MOBILE_CAMERA, latitude = here.latitude, longitude = here.longitude)
        val check = policy.checkSubmission(draft, emptyList(), emptyList(), now) as ReportCheck.Accepted
        assertEquals(now + 4 * 3_600_000L, check.expiresAtMillis)
    }

    @Test
    fun `rate limiting applies minimum interval and hourly cap`() {
        val tooSoon = policy.checkSubmission(newCamera(Fixtures.point(5_000.0)), listOf(prior(ReportType.NEW_CAMERA, 0)), emptyList(), now)
        assertTrue(tooSoon is ReportCheck.RateLimited)

        val fiveThisHour = (1..5).map { prior(ReportType.CAMERA_REMOVED, minutesAgo = it * 5L, cameraId = "c$it") }
        val capped = policy.checkSubmission(newCamera(Fixtures.point(9_000.0)), fiveThisHour, emptyList(), now)
        assertTrue(capped is ReportCheck.RateLimited)
        assertTrue((capped as ReportCheck.RateLimited).retryAfterMs > 0)
    }

    @Test
    fun `duplicate detection for repeated reports and known cameras`() {
        val sameReport = policy.checkSubmission(
            ReportDraft(ReportType.CAMERA_REMOVED, cameraId = "cam-1"),
            listOf(prior(ReportType.CAMERA_REMOVED, minutesAgo = 120, cameraId = "cam-1")),
            emptyList(),
            now,
        )
        assertEquals(DuplicateReason.SAME_REPORT_RECENTLY, (sameReport as ReportCheck.Duplicate).reason)

        val known = Fixtures.camera("known", Fixtures.point(20.0))
        val existing = policy.checkSubmission(newCamera(), emptyList(), listOf(known), now)
        assertEquals(DuplicateReason.CAMERA_ALREADY_KNOWN, (existing as ReportCheck.Duplicate).reason)
        assertEquals("known", existing.existingCameraId)

        val own = policy.checkSubmission(newCamera(), listOf(prior(ReportType.NEW_CAMERA, 90, at = Fixtures.point(15.0))), emptyList(), now)
        assertEquals(DuplicateReason.NEAR_OWN_RECENT_REPORT, (own as ReportCheck.Duplicate).reason)
    }

    @Test
    fun `invalid reports are rejected before queuing`() {
        assertEquals(InvalidReason.MISSING_LOCATION, (policy.checkSubmission(ReportDraft(ReportType.NEW_CAMERA), emptyList(), emptyList(), now) as ReportCheck.Invalid).reason)
        assertEquals(InvalidReason.MISSING_CAMERA, (policy.checkSubmission(ReportDraft(ReportType.CAMERA_REMOVED), emptyList(), emptyList(), now) as ReportCheck.Invalid).reason)
        val badLimit = ReportDraft(ReportType.INCORRECT_SPEED_LIMIT, cameraId = "c", speedLimitKmh = 63)
        assertEquals(InvalidReason.INVALID_SPEED_LIMIT, (policy.checkSubmission(badLimit, emptyList(), emptyList(), now) as ReportCheck.Invalid).reason)
        val longNote = ReportDraft(ReportType.CAMERA_REMOVED, cameraId = "c", note = "x".repeat(501))
        assertEquals(InvalidReason.NOTE_TOO_LONG, (policy.checkSubmission(longNote, emptyList(), emptyList(), now) as ReportCheck.Invalid).reason)
    }

    private fun signals(
        source: DataSource = DataSource.COMMUNITY_MODERATED,
        approved: Boolean = false,
        confirmations: Int = 0,
        disputes: Int = 0,
        reputation: Double = 0.5,
        days: Double = 0.0,
        abuse: Int = 0,
    ) = VerificationSignals(source, approved, confirmations, disputes, reputation, days, abuse)

    @Test
    fun `crowdsourced data never becomes trusted automatically`() {
        assertEquals(VerificationStatus.PENDING_VERIFICATION, policy.statusFor(signals(confirmations = 10)))
        assertEquals(VerificationStatus.PENDING_VERIFICATION, policy.statusFor(signals(approved = true, confirmations = 2)))
        assertEquals(VerificationStatus.COMMUNITY_CONFIRMED, policy.statusFor(signals(approved = true, confirmations = 3)))
    }

    @Test
    fun `disputes and abuse lower status and confidence`() {
        assertEquals(VerificationStatus.DISPUTED, policy.statusFor(signals(approved = true, confirmations = 3, disputes = 3)))
        assertEquals(VerificationStatus.REJECTED, policy.statusFor(signals(confirmations = 4, abuse = 3)))
        assertTrue(policy.confidenceScore(signals(confirmations = 4, disputes = 2)) < policy.confidenceScore(signals(confirmations = 4)))
    }

    @Test
    fun `confidence ranks sources and decays without re-verification`() {
        val official = policy.confidenceScore(signals(source = DataSource.OFFICIAL_GOVERNMENT, approved = true))
        val community = policy.confidenceScore(signals(approved = true, confirmations = 3))
        assertTrue(official > community)
        val stale = policy.confidenceScore(signals(source = DataSource.OFFICIAL_GOVERNMENT, approved = true, days = 365.0))
        assertEquals(official / 2, stale, 0.01)
        assertEquals(VerificationStatus.VERIFIED, policy.statusFor(signals(source = DataSource.OFFICIAL_GOVERNMENT, approved = true)))
    }
}
