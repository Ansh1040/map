package com.drivealert.app.reporting

import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.data.sync.SyncScheduler
import com.drivealert.app.database.CameraReportEntity
import com.drivealert.app.database.ReportAuditEntity
import com.drivealert.app.database.ReportDao
import com.drivealert.app.database.enumOrDefault
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
import com.drivealert.app.network.ReportRequestDto
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import retrofit2.HttpException

sealed interface SubmitResult {
    data class Queued(val localId: Long, val serverConfigured: Boolean) : SubmitResult
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

/**
 * Community reports. Reports are rate limited, de-duplicated and queued on
 * the device; they are reviewed by moderators on the server and never change
 * alert behaviour directly.
 */
@Singleton
class ReportRepository @Inject constructor(
    private val reportDao: ReportDao,
    private val cameraRepository: CameraRepository,
    private val policy: ReportModerationPolicy,
    private val scheduler: SyncScheduler,
    private val api: CameraDataApi,
) {
    fun observeReports(): Flow<List<ReportSummary>> = reportDao.observeAll().map { rows ->
        rows.map {
            ReportSummary(
                localId = it.localId,
                type = enumOrDefault(it.reportType, ReportType.NEW_CAMERA),
                cameraId = it.cameraId,
                status = enumOrDefault(it.status, ReportStatus.PENDING_UPLOAD),
                createdAtMillis = it.createdAt,
            )
        }
    }

    suspend fun submit(draft: ReportDraft, nowMillis: Long = System.currentTimeMillis()): SubmitResult {
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
                val id = reportDao.insertWithAudit(
                    CameraReportEntity(
                        remoteId = null,
                        reportType = draft.type.name,
                        cameraId = draft.cameraId,
                        latitude = draft.latitude,
                        longitude = draft.longitude,
                        bearingDeg = draft.bearingDeg,
                        speedLimitKmh = draft.speedLimitKmh,
                        cameraType = draft.cameraType?.name,
                        note = draft.note?.trim()?.takeIf { it.isNotEmpty() },
                        createdAt = nowMillis,
                        status = ReportStatus.PENDING_UPLOAD.name,
                        expiresAt = check.expiresAtMillis,
                        lastError = null,
                    ),
                    event = "CREATED",
                    detail = draft.type.name,
                )
                if (ApiConfig.isConfigured) scheduler.enqueueReportUpload()
                SubmitResult.Queued(id, ApiConfig.isConfigured)
            }
            is ReportCheck.RateLimited -> SubmitResult.RateLimited(check.retryAfterMs)
            is ReportCheck.Duplicate -> SubmitResult.Duplicate(check.existingCameraId, check.reason)
            is ReportCheck.Invalid -> SubmitResult.Invalid(check.reason)
        }
    }

    /** Uploads queued reports; called from [ReportUploadWorker]. */
    suspend fun uploadPending(nowMillis: Long = System.currentTimeMillis()): UploadResult {
        if (!ApiConfig.isConfigured) return UploadResult.NOT_CONFIGURED
        reportDao.expireOld(nowMillis)
        for (report in reportDao.pendingUploads()) {
            try {
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
                reportDao.update(report.copy(remoteId = response.reportId, status = status.name, lastError = null))
                reportDao.insertAudit(ReportAuditEntity(reportLocalId = report.localId, timestamp = nowMillis, event = "UPLOADED", detail = status.name))
            } catch (e: IOException) {
                return UploadResult.RETRY
            } catch (e: HttpException) {
                if (e.code() >= 500 || e.code() == 429) return UploadResult.RETRY
                reportDao.update(report.copy(status = ReportStatus.REJECTED.name, lastError = "HTTP ${e.code()}"))
                reportDao.insertAudit(ReportAuditEntity(reportLocalId = report.localId, timestamp = nowMillis, event = "REJECTED_BY_SERVER", detail = "HTTP ${e.code()}"))
            }
        }
        return UploadResult.DONE
    }

    suspend fun deleteAllLocalReports() = reportDao.deleteAll()

    private companion object {
        const val DAY_MS = 24 * 3_600_000L
    }
}
