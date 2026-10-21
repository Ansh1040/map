package com.drivealert.app.ui.report

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.drivealert.app.domain.geo.GeoPoint
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.model.SessionState
import com.drivealert.app.domain.reporting.DuplicateReason
import com.drivealert.app.domain.reporting.InvalidReason
import com.drivealert.app.domain.reporting.ReportDraft
import com.drivealert.app.domain.reporting.ReportType
import com.drivealert.app.domain.session.DrivingSessionManager
import com.drivealert.app.location.ForegroundLocationProbe
import com.drivealert.app.navigation.ReportRoute
import com.drivealert.app.reporting.ReportRepository
import com.drivealert.app.reporting.SubmitResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ReportOutcome {
    data class Queued(val serverConfigured: Boolean) : ReportOutcome
    data class RateLimited(val minutes: Int) : ReportOutcome
    data class Duplicate(val reason: DuplicateReason) : ReportOutcome
    data class Invalid(val reason: InvalidReason) : ReportOutcome
}

data class ReportUiState(
    val cameraId: String?,
    val availableTypes: List<ReportType>,
    val type: ReportType? = null,
    val speedLimitText: String = "",
    val cameraType: CameraType = CameraType.FIXED_SPEED,
    val note: String = "",
    val location: GeoPoint? = null,
    val locating: Boolean = false,
    val vehicleMoving: Boolean = false,
    val passengerConfirmed: Boolean = false,
    val submitting: Boolean = false,
    val outcome: ReportOutcome? = null,
) {
    /** Reporting is never required while driving; passengers may confirm and continue. */
    val blockedForSafety: Boolean get() = vehicleMoving && !passengerConfirmed

    val canSubmit: Boolean
        get() {
            val t = type ?: return false
            if (submitting || blockedForSafety) return false
            if (t.needsLocation && location == null) return false
            if (t == ReportType.INCORRECT_SPEED_LIMIT && speedLimitText.toIntOrNull() == null) return false
            return true
        }
}

@HiltViewModel
class ReportViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ReportRepository,
    private val probe: ForegroundLocationProbe,
    sessionManager: DrivingSessionManager,
) : ViewModel() {

    private val cameraId = savedStateHandle.toRoute<ReportRoute>().cameraId

    private val _ui = MutableStateFlow(
        ReportUiState(
            cameraId = cameraId,
            availableTypes = if (cameraId == null) {
                listOf(ReportType.NEW_CAMERA, ReportType.TEMPORARY_MOBILE_CAMERA)
            } else {
                listOf(
                    ReportType.CAMERA_REMOVED,
                    ReportType.INCORRECT_POSITION,
                    ReportType.INCORRECT_DIRECTION,
                    ReportType.INCORRECT_SPEED_LIMIT,
                    ReportType.DUPLICATE_CAMERA,
                    ReportType.ABUSE,
                )
            },
        ),
    )
    val ui: StateFlow<ReportUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            sessionManager.state.collect { state ->
                val moving = (state as? SessionState.Active)?.let { (it.speedKmh ?: 0) >= MOVING_KMH } ?: false
                _ui.update { it.copy(vehicleMoving = moving) }
            }
        }
    }

    fun selectType(type: ReportType) {
        _ui.update { it.copy(type = type, outcome = null) }
        if (type.needsLocation && _ui.value.location == null) useCurrentLocation()
    }

    fun setSpeedLimit(text: String) = _ui.update { it.copy(speedLimitText = text.filter(Char::isDigit).take(3)) }
    fun setCameraType(type: CameraType) = _ui.update { it.copy(cameraType = type) }
    fun setNote(note: String) = _ui.update { it.copy(note = note.take(500)) }
    fun setPassengerConfirmed(confirmed: Boolean) = _ui.update { it.copy(passengerConfirmed = confirmed) }

    fun useCurrentLocation() {
        viewModelScope.launch {
            _ui.update { it.copy(locating = true) }
            val fix = probe.currentLocation(maxCachedAgeMs = 30_000L)
            _ui.update { it.copy(locating = false, location = fix?.let { f -> GeoPoint(f.latitude, f.longitude) } ?: it.location) }
        }
    }

    fun submit() {
        val state = _ui.value
        val type = state.type ?: return
        if (!state.canSubmit) return
        viewModelScope.launch {
            _ui.update { it.copy(submitting = true) }
            val draft = ReportDraft(
                type = type,
                cameraId = state.cameraId.takeIf { type.needsExistingCamera },
                latitude = state.location?.latitude.takeIf { type.needsLocation },
                longitude = state.location?.longitude.takeIf { type.needsLocation },
                speedLimitKmh = state.speedLimitText.toIntOrNull().takeIf { type == ReportType.INCORRECT_SPEED_LIMIT },
                cameraType = state.cameraType.takeIf { type == ReportType.NEW_CAMERA || type == ReportType.TEMPORARY_MOBILE_CAMERA },
                note = state.note.ifBlank { null },
            )
            val outcome = when (val result = repository.submit(draft)) {
                is SubmitResult.Queued -> ReportOutcome.Queued(result.serverConfigured)
                is SubmitResult.RateLimited -> ReportOutcome.RateLimited(((result.retryAfterMs + 59_999) / 60_000).toInt())
                is SubmitResult.Duplicate -> ReportOutcome.Duplicate(result.reason)
                is SubmitResult.Invalid -> ReportOutcome.Invalid(result.reason)
            }
            _ui.update { it.copy(submitting = false, outcome = outcome) }
        }
    }

    private companion object {
        const val MOVING_KMH = 10
    }
}
