package com.drivealert.app.location.service

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.drivealert.app.di.ApplicationScope
import com.drivealert.app.domain.model.SessionEvent
import com.drivealert.app.domain.model.SessionState
import com.drivealert.app.domain.model.StopReason
import com.drivealert.app.domain.session.DrivingSessionManager
import com.drivealert.app.notifications.CameraAlertNotifier
import com.drivealert.app.notifications.SessionNotificationFactory
import com.drivealert.app.permissions.LocationPermissions
import com.drivealert.app.settings.SettingsRepository
import com.drivealert.app.util.AppLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * User-started location foreground service (type "location").
 *
 * - Started only from the visible app when the user presses "Start Drive".
 * - Shows the persistent "Camera alerts active" notification with a Stop action.
 * - Keeps running while Google Maps (or any app) is in front and with the screen locked.
 * - Returns START_NOT_STICKY and has no boot receiver: it never restarts by itself.
 */
@AndroidEntryPoint
class CameraAlertService : LifecycleService() {

    @Inject lateinit var sessionManager: DrivingSessionManager
    @Inject lateinit var sessionNotifications: SessionNotificationFactory
    @Inject lateinit var alertNotifier: CameraAlertNotifier
    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    private var sessionStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> startSession(restart = intent.getBooleanExtra(EXTRA_RESTART, false))
            ACTION_STOP -> stopSession(stopReasonFrom(intent))
            ACTION_KEEP_ALERTS -> {
                alertNotifier.cancelDriveEndCheck()
                if (sessionStarted) sessionManager.confirmDriveContinues() else stopSelf()
            }
            else -> {
                // Null intent means the system tried to recreate us. Never resume silently.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession(restart: Boolean) {
        if (sessionStarted) {
            if (restart) {
                // Fresh session state (e.g. a new simulated test drive) without leaving the foreground.
                sessionManager.stop(StopReason.USER_IN_APP)
                sessionManager.start(lifecycleScope)
            }
            return
        }
        if (!LocationPermissions.hasPreciseLocation(this)) {
            AppLog.w(TAG, "Precise location not granted; not starting")
            stopSelf()
            return
        }
        val notification = sessionNotifications.build(SessionState.Idle)
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            ServiceCompat.startForeground(this, CameraAlertNotifier.ID_SESSION, notification, type)
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException (e.g. permission revoked).
            AppLog.e(TAG, "startForeground failed", e)
            stopSelf()
            return
        }
        sessionStarted = true
        appScope.launch { settingsRepository.setSessionMarkedActive(true) }
        alertNotifier.cancelDriveEndCheck()

        sessionManager.start(lifecycleScope)

        lifecycleScope.launch {
            // Only touch the notification when its visible text changes.
            sessionManager.state
                .map { sessionNotifications.summary(it) to it }
                .distinctUntilChanged { old, new -> old.first == new.first }
                .collect { (_, state) -> updateNotification(state) }
        }
        lifecycleScope.launch {
            sessionManager.events.collect { event ->
                if (event is SessionEvent.AutoStopRequested) {
                    alertNotifier.showAutoStopped()
                    stopSession(event.reason)
                }
            }
        }
    }

    private fun updateNotification(state: SessionState) {
        if (state !is SessionState.Active) return
        if (!LocationPermissions.hasNotificationPermission(this)) return
        try {
            NotificationManagerCompat.from(this).notify(CameraAlertNotifier.ID_SESSION, sessionNotifications.build(state))
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Notification update denied")
        }
    }

    private fun stopSession(reason: StopReason) {
        sessionManager.stop(reason)
        alertNotifier.cancelDriveEndCheck()
        alertNotifier.cancelCameraAlert()
        sessionStarted = false
        appScope.launch { settingsRepository.setSessionMarkedActive(false) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (sessionStarted) {
            sessionManager.stop(StopReason.SERVICE_DESTROYED)
            sessionStarted = false
            appScope.launch { settingsRepository.setSessionMarkedActive(false) }
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CameraAlertService"
        const val ACTION_START = "com.drivealert.app.action.START_ALERTS"
        const val ACTION_STOP = "com.drivealert.app.action.STOP_ALERTS"
        const val ACTION_KEEP_ALERTS = "com.drivealert.app.action.KEEP_ALERTS"
        private const val EXTRA_STOP_REASON = "stop_reason"
        private const val EXTRA_RESTART = "restart_session"

        /**
         * Must be called from a visible activity (user-initiated).
         * @param restartSession reset per-session state if alerts are already running.
         */
        fun start(context: Context, restartSession: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CameraAlertService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESTART, restartSession),
            )
        }

        fun stop(context: Context, reason: StopReason = StopReason.USER_IN_APP) {
            context.startService(stopIntent(context, reason))
        }

        fun stopIntent(context: Context, reason: StopReason): Intent =
            Intent(context, CameraAlertService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_STOP_REASON, reason.name)

        fun keepAlertsIntent(context: Context): Intent =
            Intent(context, CameraAlertService::class.java).setAction(ACTION_KEEP_ALERTS)

        private fun stopReasonFrom(intent: Intent): StopReason =
            StopReason.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_STOP_REASON) } ?: StopReason.USER_IN_APP
    }
}
