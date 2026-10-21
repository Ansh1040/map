package com.drivealert.app.notifications

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.drivealert.app.R
import com.drivealert.app.alerts.AlertTextFormatter
import com.drivealert.app.domain.model.GpsStatus
import com.drivealert.app.domain.model.SessionState
import com.drivealert.app.domain.model.TrackingMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the persistent foreground-service notification ("Camera alerts active"). */
@Singleton
class SessionNotificationFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val text: AlertTextFormatter,
) {
    fun build(state: SessionState): Notification {
        val summary = summary(state)
        return NotificationCompat.Builder(context, NotificationChannels.SESSION)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(context.getString(R.string.session_notification_title))
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(NotificationIntents.openApp(context))
            .addAction(R.drawable.ic_action_stop, context.getString(R.string.action_stop_alerts), NotificationIntents.stopAlerts(context))
            .build()
    }

    /** Short status line; also used to decide whether the notification needs updating. */
    fun summary(state: SessionState): String {
        if (state !is SessionState.Active) return context.getString(R.string.session_status_starting)
        if (state.gpsStatus == GpsStatus.ACQUIRING) return context.getString(R.string.session_status_acquiring)
        if (state.gpsStatus == GpsStatus.LOST) return context.getString(R.string.session_status_gps_lost)
        val next = state.nextCamera
        val mode = context.getString(
            when (state.trackingMode) {
                TrackingMode.CLOSE -> R.string.mode_close
                TrackingMode.APPROACH -> R.string.mode_approach
                TrackingMode.PASSIVE, TrackingMode.OFF -> R.string.mode_passive
            },
        )
        return if (next != null && state.trackingMode == TrackingMode.CLOSE) {
            context.getString(R.string.session_status_camera_ahead, text.title(next.camera.type), text.distanceLabel(next.distanceM))
        } else {
            context.resources.getQuantityString(R.plurals.session_status_nearby, state.nearbyCameraCount, state.nearbyCameraCount, mode)
        }
    }
}
