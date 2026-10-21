package com.drivealert.app.domain.ports

import com.drivealert.app.domain.model.AlertSettings
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.CameraAlert
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.model.LocationPlan
import com.drivealert.app.domain.model.RoadMatch
import kotlinx.coroutines.flow.Flow

/**
 * Interfaces between the platform-independent domain layer and Android.
 * Keeping the session logic behind these ports lets complete drives be
 * simulated in plain JVM unit tests.
 */
interface LocationSource {
    /** Fixes produced by whichever requests are currently applied. */
    val fixes: Flow<LocationFix>

    /** Cached location if it is newer than [maxAgeMs]; never triggers a new GPS fix. */
    suspend fun lastKnownLocation(maxAgeMs: Long): LocationFix?

    /** Replace the current location requests with [plan]. */
    fun apply(plan: LocationPlan)

    /** Remove every location callback. */
    fun stop()
}

fun interface CameraLookup {
    /** Active cameras whose grid cell is in [cellIds]. */
    suspend fun camerasInCells(cellIds: List<Long>): List<Camera>
}

interface AlertPresenter {
    suspend fun prepare(settings: AlertSettings)
    suspend fun present(alert: CameraAlert, settings: AlertSettings)
    fun showDriveEndCheck()
    fun hideDriveEndCheck()
    fun clearAlerts()
    fun release()
}

fun interface AlertHistoryRecorder {
    suspend fun record(alert: CameraAlert, sessionId: String)
}

/**
 * Optional map matching. Implementations must use a licensed road-data source;
 * the app never reads the route or screen of Google Maps or any other app.
 */
fun interface RoadContextProvider {
    fun currentRoad(fix: LocationFix): RoadMatch?
}

interface Clock {
    fun nowMillis(): Long
    fun elapsedRealtimeMillis(): Long
}
