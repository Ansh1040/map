package com.drivealert.app.domain.session

import com.drivealert.app.domain.alerts.AlertThresholdPolicy
import com.drivealert.app.domain.alerts.SessionCameraTracker
import com.drivealert.app.domain.alerts.TrackerConfig
import com.drivealert.app.domain.cameras.NearbyCameraIndex
import com.drivealert.app.domain.geo.GeoMath
import com.drivealert.app.domain.location.AdaptiveLocationController
import com.drivealert.app.domain.location.DeadReckoning
import com.drivealert.app.domain.location.DeadReckoningConfig
import com.drivealert.app.domain.location.StationaryConfig
import com.drivealert.app.domain.location.StationaryDetector
import com.drivealert.app.domain.location.StationaryState
import com.drivealert.app.domain.location.TrackingInputs
import com.drivealert.app.domain.model.AlertSettings
import com.drivealert.app.domain.model.CameraAlert
import com.drivealert.app.domain.model.GpsStatus
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.model.MotionState
import com.drivealert.app.domain.model.NextCamera
import com.drivealert.app.domain.model.SessionEvent
import com.drivealert.app.domain.model.SessionState
import com.drivealert.app.domain.model.StopReason
import com.drivealert.app.domain.model.TrackingMode
import com.drivealert.app.domain.ports.AlertHistoryRecorder
import com.drivealert.app.domain.ports.AlertPresenter
import com.drivealert.app.domain.ports.Clock
import com.drivealert.app.domain.ports.LocationSource
import com.drivealert.app.domain.ports.RoadContextProvider
import com.drivealert.app.domain.relevance.CameraEvaluation
import com.drivealert.app.domain.relevance.CameraRelevanceEngine
import com.drivealert.app.domain.relevance.MotionConfig
import com.drivealert.app.domain.relevance.MotionEstimator
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SessionConfig(
    /** getLastLocation results newer than this are used at session start. */
    val lastLocationMaxAgeMs: Long = 120_000L,
    val closeTickMs: Long = 3_000L,
    val defaultTickMs: Long = 15_000L,
    val weakAccuracyM: Float = 30f,
    val gpsLostAfterActiveMs: Long = 20_000L,
    val gpsLostAfterPassiveMs: Long = 150_000L,
    /** While stationary, skip camera evaluation unless the position moved this much. */
    val stationarySkipDistanceM: Double = 10.0,
    val deadReckoning: DeadReckoningConfig = DeadReckoningConfig(),
)

/**
 * Orchestrates one user-started driving session:
 * location fix -> motion estimate -> nearby candidates (spatial index) ->
 * relevance -> per-session alert state -> presentation -> power-mode update.
 *
 * Platform independent: Android supplies the [LocationSource],
 * [AlertPresenter] and [AlertHistoryRecorder] implementations.
 */
class DrivingSessionManager(
    private val locationSource: LocationSource,
    private val locationController: AdaptiveLocationController,
    private val cameraIndex: NearbyCameraIndex,
    private val relevanceEngine: CameraRelevanceEngine,
    private val thresholdPolicy: AlertThresholdPolicy,
    private val alertPresenter: AlertPresenter,
    private val historyRecorder: AlertHistoryRecorder,
    private val settings: StateFlow<AlertSettings>,
    private val clock: Clock,
    private val dataVersion: Flow<Long> = emptyFlow(),
    private val roadContextProvider: RoadContextProvider? = null,
    private val config: SessionConfig = SessionConfig(),
    private val stationaryConfig: StationaryConfig = StationaryConfig(),
    private val trackerConfig: TrackerConfig = TrackerConfig(),
    private val motionConfig: MotionConfig = MotionConfig(),
    private val workContext: CoroutineContext = EmptyCoroutineContext,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val mutex = Mutex()
    private var job: Job? = null
    private var sessionScope: CoroutineScope? = null

    private var tracker = SessionCameraTracker(thresholdPolicy, trackerConfig)
    private var motion = MotionEstimator(motionConfig)
    private var stationary = StationaryDetector(stationaryConfig)

    @Volatile
    private var sessionId = ""
    private var startedAtMillis = 0L
    private var lastRealFix: LocationFix? = null
    private var lastEvaluatedFix: LocationFix? = null
    private var lastMotion = MotionState(null, null)
    private var lastStationary = StationaryState(false, 0L)
    private var lastAlert: CameraAlert? = null
    private var nearbyCount = 0
    private var nextCamera: NextCamera? = null
    private var awaitingDriveEndConfirmation = false
    private var autoStopRequested = false

    val isRunning: Boolean get() = job?.isActive == true

    var lastStopReason: StopReason? = null
        private set

    /** Starts a session in [scope]. Returns false if one is already running. */
    fun start(scope: CoroutineScope): Boolean {
        if (job?.isActive == true) return false
        resetSessionObjects()
        sessionId = idFactory()
        startedAtMillis = clock.nowMillis()
        publishState()

        sessionScope = scope
        job = scope.launch(workContext) {
            alertPresenter.prepare(settings.value)
            // Subscribe before any request is issued so the first fix is not lost.
            launch(start = CoroutineStart.UNDISPATCHED) {
                locationSource.fixes.collect { fix -> mutex.withLock { onFixLocked(fix) } }
            }
            launch(start = CoroutineStart.UNDISPATCHED) {
                dataVersion.drop(1).collect { mutex.withLock { cameraIndex.invalidate() } }
            }
            locationController.start()
            locationSource.lastKnownLocation(config.lastLocationMaxAgeMs)?.let { cached ->
                mutex.withLock { onFixLocked(cached) }
            }
            tickLoop()
        }
        return true
    }

    /**
     * Ends the session: cancels processing, removes every location callback and
     * clears session notifications. Safe to call repeatedly.
     */
    fun stop(reason: StopReason) {
        val wasRunning = job != null
        sessionId = ""
        job?.cancel()
        job = null
        sessionScope = null
        locationController.stop()
        if (wasRunning) lastStopReason = reason
        if (wasRunning) {
            alertPresenter.hideDriveEndCheck()
            alertPresenter.clearAlerts()
            alertPresenter.release()
        }
        _state.value = SessionState.Idle
    }

    /** The driver answered "Keep alerts on" to the drive-ended prompt. */
    fun confirmDriveContinues() {
        val scope = sessionScope ?: return
        scope.launch(workContext) {
            mutex.withLock {
                stationary.restartTimer(clock.elapsedRealtimeMillis())
                lastStationary = stationary.state(clock.elapsedRealtimeMillis())
                awaitingDriveEndConfirmation = false
                autoStopRequested = false
                alertPresenter.hideDriveEndCheck()
                publishState()
            }
        }
    }

    private fun resetSessionObjects() {
        tracker = SessionCameraTracker(thresholdPolicy, trackerConfig)
        motion = MotionEstimator(motionConfig)
        stationary = StationaryDetector(stationaryConfig)
        cameraIndex.clear()
        lastRealFix = null
        lastEvaluatedFix = null
        lastMotion = MotionState(null, null)
        lastStationary = StationaryState(false, 0L)
        lastAlert = null
        nearbyCount = 0
        nextCamera = null
        awaitingDriveEndConfirmation = false
        autoStopRequested = false
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive) {
            val wait = if (locationController.mode == TrackingMode.CLOSE) config.closeTickMs else config.defaultTickMs
            delay(wait)
            mutex.withLock { onTickLocked() }
        }
    }

    private suspend fun onFixLocked(fix: LocationFix) {
        val previous = lastRealFix
        if (previous != null && fix.elapsedRealtimeMillis <= previous.elapsedRealtimeMillis) return // duplicate/out of order
        lastRealFix = fix
        locationController.onFix(fix)
        lastMotion = motion.update(fix)
        lastStationary = stationary.update(fix)

        if (!lastStationary.isStationary && awaitingDriveEndConfirmation) {
            awaitingDriveEndConfirmation = false
            alertPresenter.hideDriveEndCheck()
        }

        // Pause expensive work while parked / waiting at a long signal.
        val lastEvaluated = lastEvaluatedFix
        if (lastStationary.isStationary && lastEvaluated != null &&
            GeoMath.distanceMeters(lastEvaluated.latitude, lastEvaluated.longitude, fix.latitude, fix.longitude) <
            config.stationarySkipDistanceM
        ) {
            publishState()
            return
        }
        evaluateLocked(fix)
    }

    private suspend fun onTickLocked() {
        val now = clock.elapsedRealtimeMillis()
        locationController.onTick()

        // Short GPS loss (tunnel/underpass) while close to a camera: dead-reckon.
        val last = lastRealFix
        val dr = config.deadReckoning
        if (locationController.mode == TrackingMode.CLOSE && last != null) {
            val gap = now - last.elapsedRealtimeMillis
            val heading = motion.current(now).heading ?: lastMotion.heading
            val speed = lastMotion.speedMps ?: 0.0
            if (gap >= dr.gapBeforeEstimateMs && gap <= dr.maxEstimateMs && heading != null && speed >= dr.minSpeedMps) {
                evaluateLocked(DeadReckoning.predict(last, heading.bearingDeg, speed, gap, dr))
            }
        }

        lastStationary = stationary.state(now)
        val stationaryFor = lastStationary.stationaryForMs
        if (stationaryFor >= stationary.autoStopAfterMs) {
            if (!autoStopRequested) {
                autoStopRequested = true
                _events.tryEmit(SessionEvent.AutoStopRequested(StopReason.STATIONARY_TIMEOUT))
            }
        } else if (stationaryFor >= stationary.promptAfterMs && !awaitingDriveEndConfirmation) {
            awaitingDriveEndConfirmation = true
            alertPresenter.showDriveEndCheck()
        }
        publishState()
    }

    private suspend fun evaluateLocked(fix: LocationFix) {
        val nowMillis = clock.nowMillis()
        val nowElapsed = clock.elapsedRealtimeMillis()
        val currentSettings = settings.value
        val motionState = if (fix.isEstimated) lastMotion else motion.current(fix.elapsedRealtimeMillis)

        val candidates = cameraIndex.candidatesNear(fix.latitude, fix.longitude, relevanceEngine.searchRadiusM)
        val road = roadContextProvider?.currentRoad(fix)
        val evaluations = relevanceEngine.evaluate(
            fix = fix,
            heading = motionState.heading,
            candidates = candidates,
            nowMillis = nowMillis,
            includeUnverified = currentSettings.includeUnverified,
            roadMatch = road,
        )
        val speedMps = motionState.speedMps ?: 0.0
        val result = tracker.process(
            evaluations = evaluations,
            fix = fix,
            speedMps = speedMps,
            thresholdsM = currentSettings.thresholdsM,
            updateIntervalSeconds = locationController.expectedIntervalSeconds,
            nowMillis = nowElapsed,
        )

        val speedKmh = motionState.speedMps?.let { (it * 3.6).roundToInt() }
        for (pending in result.alerts) {
            val limit = pending.camera.speedLimitKmh
            val alert = CameraAlert(
                camera = pending.camera,
                distanceM = AlertThresholdPolicy.roundForAnnouncement(pending.distanceM),
                thresholdM = pending.thresholdM,
                speedKmh = speedKmh,
                isOverSpeedLimit = limit != null && speedKmh != null && speedKmh > limit,
                isFirstForCamera = pending.isFirstForCamera,
                isEstimatedPosition = fix.isEstimated,
                timestampMillis = nowMillis,
            )
            lastAlert = alert
            val scope = sessionScope
            if (scope != null) {
                // Presentation (TTS, chime) must not block location processing.
                scope.launch(workContext) {
                    runCatching { alertPresenter.present(alert, currentSettings) }
                    runCatching { historyRecorder.record(alert, sessionId) }
                }
            }
        }

        if (!fix.isEstimated) lastEvaluatedFix = fix

        val active = evaluations.filter { !tracker.isSuppressed(it.camera.id, nowElapsed) }
        val eligible = active.filter { isEligible(it, currentSettings) }
        val nearestAny = eligible.firstOrNull()?.distanceM
        val nearestCandidate = eligible.filter { it.isApproachCandidate }.minOfOrNull { it.distanceM }

        if (!fix.isEstimated) {
            locationController.update(
                TrackingInputs(
                    nearestCameraM = nearestAny,
                    nearestApproachCandidateM = nearestCandidate,
                    speedMps = speedMps,
                    isStationary = lastStationary.isStationary,
                    stationaryForMs = lastStationary.stationaryForMs,
                    primaryAlertDistanceM = currentSettings.primaryAlertDistanceM,
                ),
            )
        }

        nearbyCount = evaluations.count { it.camera.verificationStatus.isTrusted }
        nextCamera = (eligible.firstOrNull { it.isAlertable } ?: eligible.firstOrNull { it.isApproachCandidate && it.position != null })
            ?.let { NextCamera(it.camera, AlertThresholdPolicy.roundForAnnouncement(it.distanceM)) }
        publishState()
    }

    private fun isEligible(evaluation: CameraEvaluation, settings: AlertSettings): Boolean =
        evaluation.camera.verificationStatus.isTrusted || settings.includeUnverified

    private fun publishState() {
        if (sessionId.isEmpty()) return
        val now = clock.elapsedRealtimeMillis()
        val fix = lastRealFix
        _state.value = SessionState.Active(
            sessionId = sessionId,
            startedAtMillis = startedAtMillis,
            trackingMode = locationController.mode.takeIf { it != TrackingMode.OFF } ?: TrackingMode.PASSIVE,
            gpsStatus = gpsStatus(fix, now),
            accuracyM = fix?.accuracyM,
            speedKmh = lastMotion.speedMps?.let { (it * 3.6).roundToInt() },
            nearbyCameraCount = nearbyCount,
            nextCamera = nextCamera,
            lastAlert = lastAlert,
            passedCameraCount = tracker.passedCount,
            isStationary = lastStationary.isStationary,
            awaitingDriveEndConfirmation = awaitingDriveEndConfirmation,
        )
    }

    private fun gpsStatus(fix: LocationFix?, nowElapsed: Long): GpsStatus {
        if (fix == null) return GpsStatus.ACQUIRING
        val lostAfter = if (locationController.mode == TrackingMode.PASSIVE) {
            config.gpsLostAfterPassiveMs
        } else {
            config.gpsLostAfterActiveMs
        }
        return when {
            nowElapsed - fix.elapsedRealtimeMillis > lostAfter -> GpsStatus.LOST
            fix.accuracyM > config.weakAccuracyM -> GpsStatus.WEAK
            else -> GpsStatus.GOOD
        }
    }
}
