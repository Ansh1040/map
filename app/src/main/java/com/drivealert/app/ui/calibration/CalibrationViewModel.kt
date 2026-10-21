package com.drivealert.app.ui.calibration

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drivealert.app.battery.BatteryRestrictionChecker
import com.drivealert.app.battery.DeviceReadiness
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.location.ForegroundLocationProbe
import com.drivealert.app.location.service.CameraAlertService
import com.drivealert.app.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class GpsQuality { NONE, WEAK, FAIR, GOOD, EXCELLENT }

data class CalibrationUiState(
    val readiness: DeviceReadiness? = null,
    val accuracyM: Float? = null,
    val quality: GpsQuality = GpsQuality.NONE,
    val secondsElapsed: Int = 0,
) {
    val locationReady: Boolean get() = readiness?.canStartAlerts == true

    /** Start is allowed with fair GPS, or after a short wait even if GPS stays weak. */
    val canStart: Boolean get() = locationReady && (quality >= GpsQuality.FAIR || secondsElapsed >= START_ANYWAY_AFTER_S)

    val isStartAnyway: Boolean get() = canStart && quality < GpsQuality.FAIR

    companion object {
        const val START_ANYWAY_AFTER_S = 20
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalibrationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: ForegroundLocationProbe,
    private val settingsRepository: SettingsRepository,
    private val checker: BatteryRestrictionChecker,
) : ViewModel() {

    private val readiness = MutableStateFlow(checker.check())
    private val startedAt = SystemClock.elapsedRealtime()

    private val fixes = readiness.flatMapLatest { r ->
        if (r.canStartAlerts) {
            probe.calibrationUpdates().map<LocationFix, LocationFix?> { it }.onStart { emit(null) }.catch { emit(null) }
        } else {
            flowOf(null)
        }
    }

    private val ticker = flow {
        while (true) {
            emit(((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt())
            delay(1_000)
        }
    }

    private var lastFix: LocationFix? = null

    val ui: StateFlow<CalibrationUiState> = combine(readiness, fixes, ticker) { r, fix, seconds ->
        if (fix != null) lastFix = fix
        val accuracy = lastFix?.accuracyM
        CalibrationUiState(
            readiness = r,
            accuracyM = accuracy,
            quality = qualityFor(accuracy),
            secondsElapsed = seconds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), CalibrationUiState(readiness = readiness.value))

    fun refreshReadiness() {
        readiness.value = checker.check()
    }

    /** User pressed "Start Drive" on the visible screen: start the foreground service. */
    fun startDrive() {
        viewModelScope.launch { settingsRepository.setLastCalibration(System.currentTimeMillis()) }
        CameraAlertService.start(context)
    }

    companion object {
        fun qualityFor(accuracyM: Float?): GpsQuality = when {
            accuracyM == null -> GpsQuality.NONE
            accuracyM <= 10f -> GpsQuality.EXCELLENT
            accuracyM <= 20f -> GpsQuality.GOOD
            accuracyM <= 50f -> GpsQuality.FAIR
            else -> GpsQuality.WEAK
        }
    }
}
