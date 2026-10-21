package com.drivealert.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivealert.app.data.history.AlertHistoryRepository
import com.drivealert.app.data.sync.CameraSyncRepository
import com.drivealert.app.data.sync.SyncScheduler
import android.content.Context
import com.drivealert.app.data.testdata.DriveSimulator
import com.drivealert.app.data.testdata.SimulationState
import com.drivealert.app.data.testdata.TestDataTools
import com.drivealert.app.location.service.CameraAlertService
import com.drivealert.app.permissions.LocationPermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import com.drivealert.app.domain.model.AlertSettings
import com.drivealert.app.location.ForegroundLocationProbe
import com.drivealert.app.network.ApiConfig
import com.drivealert.app.reporting.ReportRepository
import com.drivealert.app.settings.AppPreferences
import com.drivealert.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val history: AlertHistoryRepository,
    private val reports: ReportRepository,
    private val syncScheduler: SyncScheduler,
    syncRepository: CameraSyncRepository,
    private val testDataTools: TestDataTools,
    private val probe: ForegroundLocationProbe,
    private val simulator: DriveSimulator,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val alertSettings: StateFlow<AlertSettings> =
        settings.alertSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertSettings())

    val appPreferences: StateFlow<AppPreferences> =
        settings.appPreferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppPreferences())

    val lastUpdated: StateFlow<Long?> =
        syncRepository.observeLastUpdated().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Default data source is OpenStreetMap; a custom server is optional. */
    val usesOpenStreetMap: Boolean = !ApiConfig.isConfigured
    val testToolsAvailable: Boolean = testDataTools.isAvailable
    val simulatorAvailable: Boolean = simulator.isAvailable
    val simulation: StateFlow<SimulationState> = simulator.state

    private val _message = MutableStateFlow<SettingsMessage?>(null)
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()

    fun setVoice(enabled: Boolean) = launch { settings.setVoiceAlerts(enabled) }
    fun setSound(enabled: Boolean) = launch { settings.setAlertSound(enabled) }
    fun setVibration(enabled: Boolean) = launch { settings.setVibration(enabled) }
    fun setVisualOnly(enabled: Boolean) = launch { settings.setVisualOnly(enabled) }
    fun setDistance(meters: Int) = launch { settings.setPrimaryAlertDistance(meters) }
    fun setCloserReminders(enabled: Boolean) = launch { settings.setCloserReminders(enabled) }
    fun setIncludeUnverified(enabled: Boolean) = launch { settings.setIncludeUnverified(enabled) }

    fun setMobileDataSync(enabled: Boolean) = launch {
        settings.setAllowMobileDataSync(enabled)
        syncScheduler.schedulePeriodicSync(enabled)
    }

    fun syncNow() {
        syncScheduler.syncNow()
        _message.value = SettingsMessage.SYNC_REQUESTED
    }

    fun deleteLocalHistory() = launch {
        history.clear()
        reports.deleteAllLocalReports()
        _message.value = SettingsMessage.HISTORY_DELETED
    }

    /** DEBUG BUILDS ONLY: place the TEST-ONLY synthetic scenario ahead of the current position. */
    fun loadTestCameras() = launch {
        val fix = probe.currentLocation(maxCachedAgeMs = 60_000L)
        if (fix == null) {
            _message.value = SettingsMessage.NO_LOCATION
            return@launch
        }
        testDataTools.loadSyntheticCamerasAround(fix.latitude, fix.longitude, fix.bearingDeg?.toDouble() ?: 0.0)
        _message.value = SettingsMessage.TEST_DATA_LOADED
    }

    fun clearTestCameras() = launch {
        testDataTools.clearSyntheticCameras()
        _message.value = SettingsMessage.TEST_DATA_CLEARED
    }

    /** DEBUG BUILDS ONLY: mock-GPS test drive past the TEST-ONLY cameras; starts alerts too. */
    fun startSimulation() {
        if (!LocationPermissions.hasPreciseLocation(appContext)) {
            _message.value = SettingsMessage.NO_LOCATION
            return
        }
        if (simulator.state.value.isActive) return
        // Start (or reset) alerts synchronously, while the Settings screen is visible:
        // a foreground service may not be started once the app is in the background.
        CameraAlertService.start(appContext, restartSession = true)
        simulator.start()
        _message.value = SettingsMessage.SIMULATION_STARTED
    }

    init {
        viewModelScope.launch {
            // Only changes after this screen opened: the simulator is a singleton whose state still
            // holds the outcome of an earlier attempt, which must not reappear as a new message.
            simulator.state.drop(1).collect { state ->
                if (state is SimulationState.MockLocationNotAllowed) _message.value = SettingsMessage.MOCK_LOCATION_NEEDED
            }
        }
    }

    fun stopSimulation() = simulator.stop()

    fun consumeMessage() {
        _message.value = null
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

enum class SettingsMessage { SYNC_REQUESTED, HISTORY_DELETED, NO_LOCATION, TEST_DATA_LOADED, TEST_DATA_CLEARED, SIMULATION_STARTED, MOCK_LOCATION_NEEDED }
