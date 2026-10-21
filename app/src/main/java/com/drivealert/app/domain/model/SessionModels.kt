package com.drivealert.app.domain.model

data class CameraAlert(
    val camera: Camera,
    /** Actual distance at the time of the alert, rounded for display/voice. */
    val distanceM: Int,
    /** Configured warning band that triggered this alert (1000/500/200). */
    val thresholdM: Int,
    val speedKmh: Int?,
    val isOverSpeedLimit: Boolean,
    val isFirstForCamera: Boolean,
    val isEstimatedPosition: Boolean,
    val timestampMillis: Long,
)

data class NextCamera(
    val camera: Camera,
    val distanceM: Int,
)

sealed interface SessionState {
    data object Idle : SessionState

    data class Active(
        val sessionId: String,
        val startedAtMillis: Long,
        val trackingMode: TrackingMode,
        val gpsStatus: GpsStatus,
        val accuracyM: Float?,
        val speedKmh: Int?,
        val nearbyCameraCount: Int,
        val nextCamera: NextCamera?,
        val lastAlert: CameraAlert?,
        val passedCameraCount: Int,
        val isStationary: Boolean,
        val awaitingDriveEndConfirmation: Boolean,
    ) : SessionState
}

enum class StopReason {
    USER_IN_APP,
    USER_NOTIFICATION,
    USER_CONFIRMED_DRIVE_ENDED,
    STATIONARY_TIMEOUT,
    PERMISSION_LOST,
    SERVICE_DESTROYED,
}

sealed interface SessionEvent {
    data class AutoStopRequested(val reason: StopReason) : SessionEvent
}
