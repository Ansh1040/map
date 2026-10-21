package com.drivealert.app.reporting

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ReportUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reportRepository: ReportRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = when (reportRepository.uploadPending()) {
        UploadResult.NOT_CONFIGURED, UploadResult.DONE -> Result.success()
        UploadResult.RETRY -> if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}
