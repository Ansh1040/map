package com.drivealert.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.drivealert.app.data.history.AlertHistoryRepository
import com.drivealert.app.data.sync.SyncScheduler
import com.drivealert.app.di.ApplicationScope
import com.drivealert.app.notifications.NotificationChannels
import com.drivealert.app.settings.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class DriveAlertApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var historyRepository: AlertHistoryRepository

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
            // Deferrable, batched sync only; nothing here tracks location.
            syncScheduler.schedulePeriodicSync(prefs.allowMobileDataSync)
            historyRepository.prune(prefs.historyRetentionDays)
        }
    }
}
