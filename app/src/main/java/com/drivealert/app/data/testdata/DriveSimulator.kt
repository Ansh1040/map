package com.drivealert.app.data.testdata

import kotlinx.coroutines.flow.StateFlow

sealed interface SimulationState {
    data object Idle : SimulationState
    data object Starting : SimulationState
    data class Running(val travelledM: Int, val totalM: Int, val speedKmh: Int) : SimulationState
    data object Finished : SimulationState

    /** Drive Alert (debug) is not the selected mock location app. */
    data object MockLocationNotAllowed : SimulationState

    val isActive: Boolean get() = this is Starting || this is Running
}

/**
 * DEBUG BUILDS ONLY: replays a straight test drive past the TEST-ONLY
 * synthetic cameras with mock GPS, so alerts can be checked on a phone
 * (Google Maps in front, screen locked, …) without driving. Release builds
 * bind a no-op implementation.
 */
interface DriveSimulator {
    val isAvailable: Boolean
    val state: StateFlow<SimulationState>

    /** Starts a drive unless one is starting or running. Never suspends; progress is in [state]. */
    fun start()

    /** Cancels a starting or running drive. */
    fun stop()
}
