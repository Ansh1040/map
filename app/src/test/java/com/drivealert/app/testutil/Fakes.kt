package com.drivealert.app.testutil

import com.drivealert.app.domain.model.AlertSettings
import com.drivealert.app.domain.model.Camera
import com.drivealert.app.domain.model.CameraAlert
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.model.LocationPlan
import com.drivealert.app.domain.ports.AlertHistoryRecorder
import com.drivealert.app.domain.ports.AlertPresenter
import com.drivealert.app.domain.ports.CameraLookup
import com.drivealert.app.domain.ports.Clock
import com.drivealert.app.domain.ports.LocationSource
import com.drivealert.app.domain.geo.GridCell
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeLocationSource : LocationSource {
    val flow = MutableSharedFlow<LocationFix>(extraBufferCapacity = 64)
    override val fixes: Flow<LocationFix> = flow

    val appliedPlans = ArrayList<LocationPlan>()
    var stopCount = 0
    var lastKnown: LocationFix? = null
    val currentPlan: LocationPlan? get() = if (active) appliedPlans.lastOrNull() else null
    private var active = false

    override suspend fun lastKnownLocation(maxAgeMs: Long): LocationFix? = lastKnown

    override fun apply(plan: LocationPlan) {
        appliedPlans += plan
        active = true
    }

    override fun stop() {
        stopCount++
        active = false
    }

    fun emit(fix: LocationFix) {
        check(flow.tryEmit(fix)) { "fix dropped" }
    }
}

class FakeClock(private val elapsed: () -> Long) : Clock {
    override fun nowMillis(): Long = 1_700_000_000_000L + elapsed()
    override fun elapsedRealtimeMillis(): Long = elapsed()
}

class RecordingPresenter : AlertPresenter {
    val alerts = ArrayList<CameraAlert>()
    var prepared = 0
    var released = 0
    var driveEndShown = 0
    var driveEndHidden = 0
    var cleared = 0

    override suspend fun prepare(settings: AlertSettings) { prepared++ }
    override suspend fun present(alert: CameraAlert, settings: AlertSettings) { alerts += alert }
    override fun showDriveEndCheck() { driveEndShown++ }
    override fun hideDriveEndCheck() { driveEndHidden++ }
    override fun clearAlerts() { cleared++ }
    override fun release() { released++ }
}

class RecordingHistory : AlertHistoryRecorder {
    val recorded = ArrayList<Pair<CameraAlert, String>>()
    override suspend fun record(alert: CameraAlert, sessionId: String) {
        recorded += alert to sessionId
    }
}

/** In-memory camera store that counts lookups, standing in for the Room-backed repository. */
class InMemoryCameraLookup(private val cameras: List<Camera>) : CameraLookup {
    var calls = 0
    override suspend fun camerasInCells(cellIds: List<Long>): List<Camera> {
        calls++
        val cells = cellIds.toSet()
        return cameras.filter { it.isActive && GridCell.cellId(it.latitude, it.longitude) in cells }
    }
}
