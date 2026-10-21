package com.drivealert.app.ui.home

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivealert.app.battery.BatteryRestrictionChecker
import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.domain.geo.BoundingBox
import com.drivealert.app.domain.geo.GeoPoint
import com.drivealert.app.domain.model.AlertSettings
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.SessionState
import com.drivealert.app.domain.model.StopReason
import com.drivealert.app.domain.session.DrivingSessionManager
import com.drivealert.app.location.ForegroundLocationProbe
import com.drivealert.app.location.service.CameraAlertService
import com.drivealert.app.permissions.LocationPermissions
import com.drivealert.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val hasLocationPermission: Boolean = false,
    val hasPreciseLocation: Boolean = false,
    val locationEnabled: Boolean = true,
    val notificationsAllowed: Boolean = true,
    val myLocation: GeoPoint? = null,
    val accuracyM: Float? = null,
    val locating: Boolean = false,
    val nearbyCount: Int = 0,
    val mapCameras: List<Camera> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<Camera> = emptyList(),
    val focusPoint: GeoPoint? = null,
    val totalCameras: Int = 0,
    val setupWarnings: Int = 0,
    val sessionEndedUnexpectedly: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: ForegroundLocationProbe,
    private val cameraRepository: CameraRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionManager: DrivingSessionManager,
    private val readinessChecker: BatteryRestrictionChecker,
) : ViewModel() {

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    val session: StateFlow<SessionState> = sessionManager.state

    val settings: StateFlow<AlertSettings> =
        settingsRepository.alertSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertSettings())

    private val searchQuery = MutableStateFlow("")
    private var boundsJob: Job? = null
    private var lastLocatedAt = 0L

    init {
        viewModelScope.launch {
            cameraRepository.observeActiveCount().collect { count -> _ui.update { it.copy(totalCameras = count) } }
        }
        viewModelScope.launch {
            cameraRepository.dataVersion.collect { recountNearby() }
        }
        viewModelScope.launch {
            searchQuery.debounce(250).distinctUntilChanged().collect { query ->
                val results = if (query.length >= 2) cameraRepository.search(query) else emptyList()
                _ui.update { it.copy(searchResults = results) }
            }
        }
        viewModelScope.launch {
            val prefs = settingsRepository.currentAppPreferences()
            if (prefs.sessionMarkedActive && !sessionManager.isRunning) {
                // The system ended a session without the user stopping it (e.g. aggressive battery management).
                _ui.update { it.copy(sessionEndedUnexpectedly = true) }
                settingsRepository.setSessionMarkedActive(false)
            }
        }
    }

    /** Called when the screen resumes: cheap permission/readiness checks and at most one location read. */
    fun refresh() {
        val readiness = readinessChecker.check()
        _ui.update {
            it.copy(
                hasLocationPermission = LocationPermissions.hasAnyLocation(context),
                hasPreciseLocation = readiness.preciseLocationGranted,
                locationEnabled = readiness.locationServicesEnabled,
                notificationsAllowed = readiness.notificationsAllowed,
                setupWarnings = readiness.warningCount,
            )
        }
        val now = SystemClock.elapsedRealtime()
        if (_ui.value.hasLocationPermission && readiness.locationServicesEnabled && now - lastLocatedAt > LOCATE_INTERVAL_MS) {
            lastLocatedAt = now
            locateOnce()
        }
    }

    private fun locateOnce() {
        viewModelScope.launch {
            _ui.update { it.copy(locating = true) }
            val fix = probe.currentLocation()
            _ui.update {
                it.copy(
                    locating = false,
                    myLocation = fix?.let { f -> GeoPoint(f.latitude, f.longitude) } ?: it.myLocation,
                    accuracyM = fix?.accuracyM ?: it.accuracyM,
                )
            }
            recountNearby()
        }
    }

    private suspend fun recountNearby() {
        val location = _ui.value.myLocation ?: return
        val count = cameraRepository.camerasNear(location.latitude, location.longitude, NEARBY_RADIUS_M)
            .count { it.verificationStatus.isTrusted && it.isUsable(System.currentTimeMillis()) }
        _ui.update { it.copy(nearbyCount = count) }
    }

    fun onMapIdle(bounds: BoundingBox, zoom: Float) {
        boundsJob?.cancel()
        if (zoom < MIN_MARKER_ZOOM) {
            _ui.update { it.copy(mapCameras = emptyList()) }
            return
        }
        boundsJob = viewModelScope.launch {
            val cameras = cameraRepository.camerasInBounds(bounds)
            _ui.update { it.copy(mapCameras = cameras) }
        }
    }

    fun onSearchQueryChange(query: String) {
        _ui.update { it.copy(searchQuery = query) }
        searchQuery.value = query.trim()
    }

    fun onSearchResultSelected(camera: Camera) {
        _ui.update { it.copy(searchQuery = "", searchResults = emptyList(), focusPoint = GeoPoint(camera.latitude, camera.longitude)) }
        searchQuery.value = ""
    }

    fun setVoiceAlerts(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVoiceAlerts(enabled) }
    }

    fun setAlertDistance(meters: Int) {
        viewModelScope.launch { settingsRepository.setPrimaryAlertDistance(meters) }
    }

    fun stopAlerts() {
        CameraAlertService.stop(context, StopReason.USER_IN_APP)
    }

    fun dismissUnexpectedStop() {
        _ui.update { it.copy(sessionEndedUnexpectedly = false) }
    }

    private companion object {
        const val NEARBY_RADIUS_M = 5_000.0
        const val LOCATE_INTERVAL_MS = 120_000L
        const val MIN_MARKER_ZOOM = 9f
    }
}
