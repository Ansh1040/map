package com.drivealert.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.drivealert.app.data.history.AlertHistoryRepository
import com.drivealert.app.data.sync.CameraSyncRepository
import com.drivealert.app.data.sync.SyncScheduler
import com.drivealert.app.di.ApplicationScope
import com.drivealert.app.notifications.NotificationChannels
import com.drivealert.app.reporting.ReportRepository
import com.drivealert.app.settings.SettingsRepository
import com.drivealert.app.util.AppLog
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class DriveAlertApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var historyRepository: AlertHistoryRepository
    @Inject lateinit var syncRepository: CameraSyncRepository
    @Inject lateinit var reportRepository: ReportRepository

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        appScope.launch {
            val prefs = settingsRepository.currentAppPreferences()
            // Deferrable, batched sync only; nothing here tracks location. Scheduled first so a
            // failing step below cannot leave camera updates unscheduled.
            startupStep("schedule sync") { syncScheduler.schedulePeriodicSync(prefs.allowMobileDataSync) }
            // Offline-first: load the OpenStreetMap snapshot shipped in the APK (no network;
            // a no-op once that snapshot version is imported with the current parser).
            startupStep("bundled data import") { syncRepository.importBundledOpenData() }
            // Old reports without publishing consent stay on the phone; resume consented uploads.
            startupStep("report reconcile") { reportRepository.reconcileOnStart() }
            startupStep("history prune") { historyRepository.prune(prefs.historyRetentionDays) }
        }
    }

    /** Runs one startup step; a failure is logged (step and error type only) and the next step still runs. */
    private suspend fun startupStep(name: String, step: suspend () -> Unit) {
        runCatching { step() }.onFailure { error ->
            if (error is CancellationException) throw error
            AppLog.w(TAG, "Startup step '$name' failed: ${error.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "DriveAlertApp"
    }
}
