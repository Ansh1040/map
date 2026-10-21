package com.drivealert.app.ui.home

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivealert.app.battery.BatteryRestrictionChecker
import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.domain.cameras.NearbyCamera
import com.drivealert.app.domain.cameras.NearbyCameras
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /** Nearest cameras with distance and direction from the user (for the list and map framing). */
    val nearbyCameras: List<NearbyCamera> = emptyList(),
    /**
     * [nearbyCameras] was computed for a fix at most 5 minutes old (NEARBY_MAX_FIX_AGE_MS, counted from
     * when the fix was taken), so an empty list then means "none nearby". False before the first
     * read, while a read replaces a fix older than that, and after a drive ends, so rows measured from
     * an old position are never shown as current.
     */
    val nearbyLoaded: Boolean = false,
    /** Every usable camera (trusted, plus unverified ones when the user opts in); the map clusters them itself. */
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
    private var nearbyJob: Job? = null
    private var locateJob: Job? = null
    private var lastLocatedAt = 0L

    /** SystemClock.elapsedRealtime() at which the fix now in [HomeUiState.myLocation] was taken; 0 = none. */
    private var lastFixAt = 0L

    /**
     * Between [refresh] (resume or permission result) and [onPause]. A drive that ends while this is
     * false must not trigger a read: the app may be in the background.
     */
    private var resumed = false

    init {
        viewModelScope.launch {
            cameraRepository.observeActiveCount().collect { count -> _ui.update { it.copy(totalCameras = count) } }
        }
        viewModelScope.launch {
            // Initial load, then again whenever the camera data or the "include unverified" choice changes.
            val includeUnverified = settings.map { it.includeUnverified }.distinctUntilChanged()
            combine(cameraRepository.dataVersion, includeUnverified) { _, include -> include }
                .collectLatest { include ->
                    recountNearby()
                    loadMapCameras(include)
                }
        }
        viewModelScope.launch {
            searchQuery.debounce(250).distinctUntilChanged().collect { query ->
                val results = if (query.length >= 2) cameraRepository.search(query) else emptyList()
                _ui.update { it.copy(searchResults = results) }
            }
        }
        viewModelScope.launch {
            // The first state seen only arms the check, so a ViewModel created mid-session still notices the end.
            var wasActive = false
            session.map { it is SessionState.Active }.distinctUntilChanged().collect { active ->
                if (wasActive && !active) onDriveEnded()
                wasActive = active
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

    /** On resume and after a permission result: cheap permission/readiness checks and at most one location read. */
    fun refresh() {
        resumed = true
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
        // 0 = never read, or reset when a drive ends. Checked explicitly: in the first 2 minutes after
        // boot, `now - 0` is still under the interval.
        val readDue = lastLocatedAt == 0L || now - lastLocatedAt > LOCATE_INTERVAL_MS
        if (_ui.value.hasLocationPermission && readiness.locationServicesEnabled && readDue) {
            lastLocatedAt = now
            locateOnce()
        }
    }

    /** The screen paused or left the composition: a drive that ends from now on does not read location until [refresh]. */
    fun onPause() {
        resumed = false
    }

    private fun locateOnce() {
        // A newer read replaces one still running (e.g. the read forced when a drive ends).
        locateJob?.cancel()
        locateJob = viewModelScope.launch {
            // Rows measured from an old (or no) fix must not stay visible while the new read runs.
            _ui.update { it.copy(locating = true, nearbyLoaded = it.nearbyLoaded && hasFreshFix()) }
            val fix = probe.currentLocation()
            // The probe may return a cached fix up to 2 minutes old: count its age from when it was
            // taken (same clock as elapsedRealtime), not from when the read returned it.
            if (fix != null) {
                val now = SystemClock.elapsedRealtime()
                lastFixAt = fix.elapsedRealtimeMillis.takeIf { it in 1..now } ?: now
            }
            _ui.update {
                it.copy(
                    locating = false,
                    // A failed read keeps the last position: the map dot and the count chip keep their values.
                    myLocation = fix?.let { f -> GeoPoint(f.latitude, f.longitude) } ?: it.myLocation,
                    accuracyM = fix?.accuracyM ?: it.accuracyM,
                )
            }
            recountNearby()
        }
    }

    /**
     * Recomputes, for the last known location, [HomeUiState.nearbyCount] (trusted cameras within
     * [NEARBY_RADIUS_M]) and [HomeUiState.nearbyCameras], and sets [HomeUiState.nearbyLoaded] from
     * the fix's freshness: this also runs on data and setting changes, long after the read. A newer
     * request cancels an older one, so a slow lookup for an old location, data set or setting cannot
     * overwrite a newer result.
     */
    private fun recountNearby() {
        val location = _ui.value.myLocation ?: return
        val includeUnverified = settings.value.includeUnverified
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            val count = cameraRepository.camerasNear(location.latitude, location.longitude, NEARBY_RADIUS_M)
                .count { it.verificationStatus.isTrusted && it.isUsable(System.currentTimeMillis()) }
            val nearest = NearbyCameras.rank(
                latitude = location.latitude,
                longitude = location.longitude,
                cameras = cameraRepository.camerasNear(location.latitude, location.longitude, NearbyCameras.DEFAULT_RADIUS_M),
                nowMillis = System.currentTimeMillis(),
                includeUnverified = includeUnverified,
            )
            // Checked when the result lands, not when the lookup started: a drive may have ended meanwhile.
            _ui.update { it.copy(nearbyCount = count, nearbyCameras = nearest, nearbyLoaded = hasFreshFix()) }
        }
    }

    /** The fix now in [HomeUiState.myLocation] was taken at most [NEARBY_MAX_FIX_AGE_MS] ago. */
    private fun hasFreshFix(): Boolean = lastFixAt != 0L && SystemClock.elapsedRealtime() - lastFixAt <= NEARBY_MAX_FIX_AGE_MS

    /**
     * A drive just ended, and the position Home last read may be far behind the car. Forget it as a
     * fresh fix and bypass the 2-minute read throttle, so the nearby rows stay hidden until a new read.
     * Read now only if the screen is resumed: the app may be in the background, where Drive Alert never
     * reads location (it has only while-in-use permission). Otherwise the next resume reads it.
     */
    private fun onDriveEnded() {
        lastFixAt = 0L
        lastLocatedAt = 0L
        _ui.update { it.copy(nearbyLoaded = false) }
        if (resumed) refresh()
    }

    /** Every usable camera for the map, which clusters them itself (no per-viewport query, nothing hidden when zoomed out). */
    private suspend fun loadMapCameras(includeUnverified: Boolean) {
        val now = System.currentTimeMillis()
        val cameras = cameraRepository.allActive()
            .filter { it.isUsable(now) && (includeUnverified || it.verificationStatus.isTrusted) }
        _ui.update { it.copy(mapCameras = cameras) }
    }

    /**
     * Intentionally a no-op: [HomeUiState.mapCameras] already holds every usable camera and the
     * map clusters them itself, so moving or zooming needs no new query. Kept with the same
     * signature because HomeScreen still passes it to the map as its idle callback.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onMapIdle(bounds: BoundingBox, zoom: Float) = Unit

    fun onSearchQueryChange(query: String) {
        _ui.update { it.copy(searchQuery = query) }
        searchQuery.value = query.trim()
    }

    fun onSearchResultSelected(camera: Camera) {
        _ui.update { it.copy(searchQuery = "", searchResults = emptyList(), focusPoint = GeoPoint(camera.latitude, camera.longitude)) }
        searchQuery.value = ""
    }

    /** The map has moved to the selected search result; do not move it there again. */
    fun onFocusConsumed() {
        _ui.update { it.copy(focusPoint = null) }
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

        /** Nearby rows count as current only for a fix that arrived at most this long ago (5 minutes). */
        const val NEARBY_MAX_FIX_AGE_MS = 300_000L
    }
}
