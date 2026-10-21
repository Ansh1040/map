package com.drivealert.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.drivealert.app.reporting.ReportUploadScheduler
import com.drivealert.app.reporting.ReportUploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReportUploadScheduler {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Daily batched sync. Prefers Wi-Fi (unmetered) unless the user allows
     * mobile data; never runs on low battery.
     */
    fun schedulePeriodicSync(allowMobileData: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<CameraSyncWorker>(24, TimeUnit.HOURS, 6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_SYNC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** User-requested "Update now". */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<CameraSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(MANUAL_SYNC, ExistingWorkPolicy.REPLACE, request)
    }

    fun observeManualSync(): Flow<WorkInfo.State?> =
        workManager.getWorkInfosForUniqueWorkFlow(MANUAL_SYNC).map { infos -> infos.firstOrNull()?.state }

    override fun enqueueReportUpload() {
        val request = OneTimeWorkRequestBuilder<ReportUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        // APPEND_OR_REPLACE: a report queued while an upload runs still gets its own run.
        workManager.enqueueUniqueWork(REPORT_UPLOAD, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val PERIODIC_SYNC = "camera-sync-periodic"
        const val MANUAL_SYNC = "camera-sync-manual"
        const val REPORT_UPLOAD = "report-upload"
    }
}
