package com.drivealert.app.debugtools

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.SystemClock
import com.drivealert.app.data.testdata.DriveSimulator
import com.drivealert.app.data.testdata.SimulationState
import com.drivealert.app.data.testdata.TestDataTools
import com.drivealert.app.di.ApplicationScope
import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.model.StopReason
import com.drivealert.app.domain.session.DrivingSessionManager
import com.drivealert.app.location.ForegroundLocationProbe
import com.drivealert.app.location.awaitResult
import com.drivealert.app.location.service.CameraAlertService
import com.drivealert.app.util.AppLog
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DEBUG ONLY. Drives a virtual car north from the phone's position at 72 km/h
 * for 7.2 km, past the TEST-ONLY scenario (TEST-001 … TEST-006), by putting
 * the Fused Location Provider in mock mode. Every app using fused location
 * (including Google Maps) follows the virtual car until the drive ends.
 *
 * Guarantees: one drive at a time; leaving the Settings screen does not
 * cancel it; mock mode is always switched off when the drive finishes, is
 * stopped or fails; the alert session used for the drive is ended with it,
 * so stale simulated motion can never produce alerts afterwards.
 *
 * Requires Developer options → "Select mock location app" → Drive Alert (debug).
 */
@Singleton
class MockDriveSimulator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val testData: TestDataTools,
    private val probe: ForegroundLocationProbe,
    private val sessionManager: DrivingSessionManager,
    @ApplicationScope private val scope: CoroutineScope,
) : DriveSimulator {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val _state = MutableStateFlow<SimulationState>(SimulationState.Idle)
    override val state: StateFlow<SimulationState> = _state.asStateFlow()
    override val isAvailable: Boolean = true
    private var job: Job? = null

    @Synchronized
    override fun start() {
        if (job?.isActive == true) return
        _state.value = SimulationState.Starting
        job = scope.launch { runDrive() }
    }

    @Synchronized
    override fun stop() {
        job?.cancel()
    }

    @SuppressLint("MissingPermission")
    private suspend fun runDrive() {
        var mockRequested = false
        try {
            // Read the real position before mock mode replaces GPS; without a fix, use a
            // test area far from any mapped camera.
            val origin = probe.currentLocation(maxCachedAgeMs = 10 * 60_000L)
            val startLat = origin?.latitude ?: FALLBACK_LAT
            val startLon = origin?.longitude ?: FALLBACK_LON

            mockRequested = true // set before the call: the IPC may take effect even if we are cancelled
            try {
                client.setMockMode(true).awaitResult()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // SecurityException: Drive Alert (debug) is not the selected mock location app.
                _state.value = SimulationState.MockLocationNotAllowed
                return
            }
            testData.loadSyntheticCamerasAround(startLat, startLon, headingDeg = HEADING)

            var travelled = START_M
            while (travelled <= END_M) {
                val point = GeoMath.destination(startLat, startLon, HEADING, travelled)
                try {
                    client.setMockLocation(mockLocation(point.latitude, point.longitude)).awaitResult()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // e.g. the mock location app was changed mid-drive: end the drive cleanly.
                    AppLog.w(TAG, "Mock location rejected: ${e.javaClass.simpleName}")
                    break
                }
                _state.value = SimulationState.Running((travelled - START_M).toInt(), (END_M - START_M).toInt(), (SPEED_MPS * 3.6).toInt())
                delay(1_000L)
                travelled += SPEED_MPS
            }
            _state.value = SimulationState.Finished
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Simulated drive failed: ${e.javaClass.simpleName}")
            _state.value = SimulationState.Idle
        } finally {
            withContext(NonCancellable) {
                if (mockRequested) runCatching { client.setMockMode(false).awaitResult() }
                if (_state.value.isActive) _state.value = SimulationState.Idle
                // End the session that was started for this drive.
                if (sessionManager.isRunning) runCatching { CameraAlertService.stop(context, StopReason.USER_IN_APP) }
            }
        }
    }

    private fun mockLocation(lat: Double, lon: Double) = Location("fused").apply {
        latitude = lat
        longitude = lon
        accuracy = 5f
        speed = SPEED_MPS.toFloat()
        bearing = HEADING.toFloat()
        bearingAccuracyDegrees = 5f
        speedAccuracyMetersPerSecond = 0.5f
        altitude = 500.0
        verticalAccuracyMeters = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private companion object {
        const val TAG = "MockDriveSimulator"
        const val HEADING = 0.0 // north
        const val SPEED_MPS = 20.0 // 72 km/h
        const val START_M = -200.0
        const val END_M = 7_000.0 // past TEST-006 (6.5 km)
        const val FALLBACK_LAT = 23.0 // central India, > 300 km from any OSM camera
        const val FALLBACK_LON = 77.5
    }
}
