package com.drivealert.app.reporting

import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.data.testdata.SyntheticCameraDataset
import com.drivealert.app.database.CameraReportEntity
import com.drivealert.app.database.ReportAuditEntity
import com.drivealert.app.database.ReportDao
import com.drivealert.app.database.enumOrDefault
import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.reporting.DuplicateReason
import com.drivealert.app.domain.reporting.InvalidReason
import com.drivealert.app.domain.reporting.PriorReport
import com.drivealert.app.domain.reporting.ReportCheck
import com.drivealert.app.domain.reporting.ReportDraft
import com.drivealert.app.domain.reporting.ReportModerationPolicy
import com.drivealert.app.domain.reporting.ReportStatus
import com.drivealert.app.domain.reporting.ReportType
import com.drivealert.app.network.ApiConfig
import com.drivealert.app.network.CameraDataApi
import com.drivealert.app.network.HttpStatusException
import com.drivealert.app.network.OsmNotesApi
import com.drivealert.app.network.ReportRequestDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

/** Where an accepted report goes. */
enum class ReportDestination {
    /** Optional licensed backend (CAMERA_API_BASE_URL). */
    CUSTOM_SERVER,

    /** Public OpenStreetMap note reviewed by OSM volunteers (user consented). */
    OPENSTREETMAP_NOTE,

    /** Kept on this phone only. */
    DEVICE_ONLY,
}

/** Why a report the user asked to publish was kept on the phone instead. */
enum class KeptOnDeviceReason {
    /** TEST-ONLY synthetic cameras and simulated positions are never published. */
    TEST_DATA,

    /** The position came from a mock-location provider. */
    MOCK_LOCATION,

    /** The position is not precise enough for volunteers to act on. */
    LOW_ACCURACY,

    /** A wrong-position report must be made at the correct position, near the mapped camera. */
    FAR_FROM_CAMERA,
}

sealed interface SubmitResult {
    data class Queued(val localId: Long, val destination: ReportDestination, val keptReason: KeptOnDeviceReason? = null) : SubmitResult
    data class RateLimited(val retryAfterMs: Long) : SubmitResult
    data class Duplicate(val existingCameraId: String?, val reason: DuplicateReason) : SubmitResult
    data class Invalid(val reason: InvalidReason) : SubmitResult
}

enum class UploadResult { NOT_CONFIGURED, DONE, RETRY }

data class ReportSummary(
    val localId: Long,
    val type: ReportType,
    val cameraId: String?,
    val status: ReportStatus,
    val createdAtMillis: Long,
)

/** Where the reporter's position came from (only for reports that carry a position). */
data class ReportPosition(val accuracyM: Float?, val isMock: Boolean)

/** Schedules the background upload of queued reports. */
fun interface ReportUploadScheduler {
    fun enqueueReportUpload()
}

/**
 * Community reports. Reports are rate limited and de-duplicated on the
 * device, and never change alerts directly: they are reviewed by people
 * (OpenStreetMap volunteers via public notes, or the optional server's
 * moderators) and only reach alerts through the next data update.
 *
 * Consent is stored per report as its status: only [ReportStatus.PENDING_OSM_NOTE]
 * rows are ever published to OpenStreetMap.
 */
@Singleton
class ReportRepository @Inject constructor(
    private val reportDao: ReportDao,
    private val cameraRepository: CameraRepository,
    private val policy: ReportModerationPolicy,
    private val scheduler: ReportUploadScheduler,
    private val api: CameraDataApi,
    private val notes: OsmNotesApi,
) {
    private val usesOpenStreetMap: Boolean get() = !ApiConfig.isConfigured

    fun observeReports(): Flow<List<ReportSummary>> = reportDao.observeAll().map { rows ->
        rows.map {
            ReportSummary(
                localId = it.localId,
                type = enumOrDefault(it.reportType, ReportType.NEW_CAMERA),
                cameraId = it.cameraId,
                status = enumOrDefault(it.status, ReportStatus.SAVED_ON_DEVICE),
                createdAtMillis = it.createdAt,
            )
        }
    }

    /**
     * @param publishToOpenStreetMap the user's explicit consent for this report.
     * @param position accuracy/mock flag of the attached position (new, temporary and position reports).
     */
    suspend fun submit(
        draft: ReportDraft,
        publishToOpenStreetMap: Boolean = false,
        position: ReportPosition? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): SubmitResult {
        reportDao.expireOld(nowMillis)
        val prior = reportDao.createdSince(nowMillis - DAY_MS).map {
            PriorReport(
                type = enumOrDefault(it.reportType, ReportType.NEW_CAMERA),
                cameraId = it.cameraId,
                latitude = it.latitude,
                longitude = it.longitude,
                createdAtMillis = it.createdAt,
            )
        }
        val lat = draft.latitude
        val lon = draft.longitude
        val nearby = if (lat != null && lon != null) cameraRepository.camerasNear(lat, lon, 200.0) else emptyList()

        return when (val check = policy.checkSubmission(draft, prior, nearby, nowMillis)) {
            is ReportCheck.Accepted -> {
                val camera = draft.cameraId?.let { cameraRepository.getCamera(it) }
                val isTestData = camera?.isTestData == true || draft.cameraId?.startsWith(SyntheticCameraDataset.ID_PREFIX) == true
                val keptReason = when {
                    !(publishToOpenStreetMap || ApiConfig.isConfigured) -> null
                    isTestData -> KeptOnDeviceReason.TEST_DATA
                    draft.type.needsLocation && position?.isMock == true -> KeptOnDeviceReason.MOCK_LOCATION
                    draft.type.needsLocation && (position?.accuracyM == null || position.accuracyM > MAX_PUBLISH_ACCURACY_M) ->
                        KeptOnDeviceReason.LOW_ACCURACY
                    draft.type == ReportType.INCORRECT_POSITION && camera != null && lat != null && lon != null &&
                        GeoMath.distanceMeters(lat, lon, camera.latitude, camera.longitude) > MAX_POSITION_CORRECTION_M ->
                        KeptOnDeviceReason.FAR_FROM_CAMERA
                    else -> null
                }
                val destination = when {
                    keptReason != null -> ReportDestination.DEVICE_ONLY
                    ApiConfig.isConfigured -> ReportDestination.CUSTOM_SERVER
                    publishToOpenStreetMap -> ReportDestination.OPENSTREETMAP_NOTE
                    else -> ReportDestination.DEVICE_ONLY
                }
                val status = when (destination) {
                    ReportDestination.CUSTOM_SERVER -> ReportStatus.PENDING_UPLOAD
                    ReportDestination.OPENSTREETMAP_NOTE -> ReportStatus.PENDING_OSM_NOTE
                    ReportDestination.DEVICE_ONLY -> ReportStatus.SAVED_ON_DEVICE
                }
                // Location-type reports carry the reporter's chosen position; all other reports
                // are placed at the mapped camera, never at the reporter.
                val reportLat = if (draft.type.needsLocation) lat else camera?.latitude
                val reportLon = if (draft.type.needsLocation) lon else camera?.longitude
                val id = reportDao.insertWithAudit(
                    CameraReportEntity(
                        remoteId = null,
                        reportType = draft.type.name,
                        cameraId = draft.cameraId,
                        latitude = reportLat,
                        longitude = reportLon,
                        bearingDeg = draft.bearingDeg,
                        speedLimitKmh = draft.speedLimitKmh,
                        cameraType = draft.cameraType?.name,
                        note = draft.note?.trim()?.takeIf { it.isNotEmpty() },
                        createdAt = nowMillis,
                        status = status.name,
                        expiresAt = check.expiresAtMillis,
                        lastError = keptReason?.name,
                    ),
                    event = "CREATED",
                    detail = "${draft.type.name} -> ${destination.name}" + (keptReason?.let { " ($it)" } ?: ""),
                )
                if (destination != ReportDestination.DEVICE_ONLY) scheduler.enqueueReportUpload()
                SubmitResult.Queued(id, destination, keptReason)
            }
            is ReportCheck.RateLimited -> SubmitResult.RateLimited(check.retryAfterMs)
            is ReportCheck.Duplicate -> SubmitResult.Duplicate(check.existingCameraId, check.reason)
            is ReportCheck.Invalid -> SubmitResult.Invalid(check.reason)
        }
    }

    /**
     * App start: reports saved by older versions as PENDING_UPLOAD never had publishing
     * consent; in OpenStreetMap mode they stay on the phone. Then resume any consented uploads.
     */
    suspend fun reconcileOnStart(nowMillis: Long = System.currentTimeMillis()) {
        if (usesOpenStreetMap) {
            for (row in reportDao.withStatus(ReportStatus.PENDING_UPLOAD.name)) {
                reportDao.update(row.copy(status = ReportStatus.SAVED_ON_DEVICE.name))
                reportDao.insertAudit(ReportAuditEntity(reportLocalId = row.localId, timestamp = nowMillis, event = "KEPT_ON_DEVICE", detail = "no publishing consent recorded"))
            }
        }
        if (reportDao.withStatus(pendingStatus().name).isNotEmpty()) scheduler.enqueueReportUpload()
    }

    /** Uploads queued reports; called from [ReportUploadWorker]. */
    suspend fun uploadPending(nowMillis: Long = System.currentTimeMillis()): UploadResult {
        reportDao.expireOld(nowMillis)
        val pending = reportDao.withStatus(pendingStatus().name)
        for (report in pending) {
            val outcome = if (usesOpenStreetMap) publishOsmNote(report, nowMillis) else uploadToServer(report, nowMillis)
            if (outcome == UploadResult.RETRY) return UploadResult.RETRY
        }
        return UploadResult.DONE
    }

    private fun pendingStatus(): ReportStatus = if (usesOpenStreetMap) ReportStatus.PENDING_OSM_NOTE else ReportStatus.PENDING_UPLOAD

    private suspend fun uploadToServer(report: CameraReportEntity, nowMillis: Long): UploadResult = try {
        val response = api.submitReport(
            ReportRequestDto(
                clientReportId = "r-${report.localId}-${report.createdAt}",
                type = report.reportType,
                cameraId = report.cameraId,
                latitude = report.latitude,
                longitude = report.longitude,
                bearingDeg = report.bearingDeg,
                speedLimitKmh = report.speedLimitKmh,
                cameraType = report.cameraType,
                note = report.note,
                createdAt = report.createdAt,
            ),
        )
        val status = ReportStatus.entries.firstOrNull { it.name == response.status } ?: ReportStatus.SUBMITTED
        markUploaded(report, response.reportId, status, "UPLOADED", nowMillis)
        UploadResult.DONE
    } catch (e: IOException) {
        UploadResult.RETRY
    } catch (e: HttpException) {
        if (e.code() >= 500 || e.code() == 429) UploadResult.RETRY else markRejected(report, e.code(), nowMillis)
    }

    private suspend fun publishOsmNote(report: CameraReportEntity, nowMillis: Long): UploadResult {
        val lat = report.latitude
        val lon = report.longitude
        if (lat == null || lon == null) {
            reportDao.update(report.copy(status = ReportStatus.SAVED_ON_DEVICE.name, lastError = "No location for a public note"))
            reportDao.insertAudit(ReportAuditEntity(reportLocalId = report.localId, timestamp = nowMillis, event = "KEPT_ON_DEVICE", detail = "no location"))
            return UploadResult.DONE
        }
        val reference = OsmNoteText.reference(report.localId, report.createdAt)
        val alreadyAttempted = reportDao.auditFor(report.localId).any { it.event == EVENT_ATTEMPT }
        if (alreadyAttempted) {
            // A previous POST may have succeeded with the response lost: adopt it instead of re-posting.
            val existing = try {
                notes.findNoteByToken(reference)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Any failed search (HTTP error of any code, network, unreadable body) means the
                // note may or may not exist: try again later; never re-post or reject on it.
                return UploadResult.RETRY
            }
            if (existing != null) {
                markUploaded(report, "osm-note-$existing", ReportStatus.UNDER_REVIEW, "PUBLISHED_OSM_NOTE", nowMillis)
                return UploadResult.DONE
            }
        }
        return try {
            reportDao.insertAudit(ReportAuditEntity(reportLocalId = report.localId, timestamp = nowMillis, event = EVENT_ATTEMPT, detail = reference))
            val text = OsmNoteText.build(
                type = enumOrDefault(report.reportType, ReportType.NEW_CAMERA),
                cameraId = report.cameraId,
                cameraType = report.cameraType,
                speedLimitKmh = report.speedLimitKmh,
                comment = report.note,
                reference = reference,
            )
            val noteId = notes.createNote(lat, lon, text)
            markUploaded(report, noteId?.let { "osm-note-$it" } ?: "osm-note-unknown", ReportStatus.UNDER_REVIEW, "PUBLISHED_OSM_NOTE", nowMillis)
            UploadResult.DONE
        } catch (e: HttpStatusException) {
            // 4xx from the POST: the server refused it, so no note exists; do not loop.
            if (e.code >= 500 || e.code == 429) UploadResult.RETRY else markRejected(report, e.code, nowMillis)
        } catch (e: IOException) {
            UploadResult.RETRY
        }
    }

    private suspend fun markUploaded(report: CameraReportEntity, remoteId: String, status: ReportStatus, event: String, nowMillis: Long) {
        reportDao.update(report.copy(remoteId = remoteId, status = status.name, lastError = null))
        reportDao.insertAudit(ReportAuditEntity(reportLocalId = report.localId, timestamp = nowMillis, event = event, detail = remoteId))
    }

    private suspend fun markRejected(report: CameraReportEntity, code: Int, nowMillis: Long): UploadResult {
        reportDao.update(report.copy(status = ReportStatus.REJECTED.name, lastError = "HTTP $code"))
        reportDao.insertAudit(ReportAuditEntity(reportLocalId = report.localId, timestamp = nowMillis, event = "REJECTED_BY_SERVER", detail = "HTTP $code"))
        return UploadResult.DONE
    }

    suspend fun deleteAllLocalReports() = reportDao.deleteAll()

    companion object {
        private const val DAY_MS = 24 * 3_600_000L
        private const val EVENT_ATTEMPT = "OSM_NOTE_ATTEMPT"

        /** Positions less precise than this are not published (volunteers could map the wrong road). */
        const val MAX_PUBLISH_ACCURACY_M = 30f

        /** A wrong-position report must be made within this distance of the mapped camera. */
        const val MAX_POSITION_CORRECTION_M = 300.0
    }
}
