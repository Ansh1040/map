package com.drivealert.app.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Deferrable camera-database synchronisation. Runs only under WorkManager
 * constraints (unmetered network unless the user allows mobile data, battery
 * not low) and never during an alert session's location processing.
 */
@HiltWorker
class CameraSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncRepository: CameraSyncRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val catalog = syncRepository.refreshCatalog()
        if (catalog is SyncResult.NotConfigured) return Result.success()
        return when (val result = syncRepository.syncInstalledPackages()) {
            SyncResult.NotConfigured, is SyncResult.Success -> Result.success()
            is SyncResult.Failure -> if (result.retryable && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
