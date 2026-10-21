package com.drivealert.app.notifications

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.drivealert.app.R
import com.drivealert.app.alerts.AlertTextFormatter
import com.drivealert.app.domain.model.CameraAlert
import com.drivealert.app.permissions.LocationPermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Heads-up camera warnings and drive-status notifications.
 *
 * Deliberately never uses a full-screen intent: warnings must not take over
 * the screen (or the navigation app) while driving.
 */
@Singleton
class CameraAlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val text: AlertTextFormatter,
) {
    private val manager get() = NotificationManagerCompat.from(context)

    fun buildCameraAlert(alert: CameraAlert) = NotificationCompat.Builder(context, NotificationChannels.CAMERA_ALERTS)
        .setSmallIcon(R.drawable.ic_stat_camera)
        .setColor(ContextCompat.getColor(context, R.color.alert_amber))
        .setContentTitle(text.title(alert.camera.type))
        .setContentText(text.body(alert))
        .setSubText(context.getString(R.string.app_name))
        .setStyle(NotificationCompat.BigTextStyle().bigText(text.body(alert) + "\n" + text.detail(alert)))
        .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(true)
        .setOnlyAlertOnce(false)
        // Do NOT call setSilent(): it places the notification in a silent group,
        // which makes SystemUI suppress the heads-up. The channel itself has no
        // sound; the chime/voice play on the navigation-guidance stream instead.
        .setTimeoutAfter(ALERT_TIMEOUT_MS)
        .setContentIntent(NotificationIntents.openApp(context))
        .build()

    @SuppressLint("MissingPermission")
    fun showCameraAlert(alert: CameraAlert) {
        if (!canNotify()) return
        manager.notify(ID_CAMERA_ALERT, buildCameraAlert(alert))
    }

    fun cancelCameraAlert() = manager.cancel(ID_CAMERA_ALERT)

    @SuppressLint("MissingPermission")
    fun showDriveEndCheck() {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, NotificationChannels.DRIVE_STATUS)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(context.getString(R.string.drive_end_title))
            .setContentText(context.getString(R.string.drive_end_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.drive_end_text)))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(NotificationIntents.openApp(context))
            .addAction(R.drawable.ic_action_stop, context.getString(R.string.action_end_drive), NotificationIntents.endDrive(context))
            .addAction(R.drawable.ic_stat_camera, context.getString(R.string.action_keep_alerts), NotificationIntents.keepAlerts(context))
            .build()
        manager.notify(ID_DRIVE_END_CHECK, notification)
    }

    fun cancelDriveEndCheck() = manager.cancel(ID_DRIVE_END_CHECK)

    @SuppressLint("MissingPermission")
    fun showAutoStopped() {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(context, NotificationChannels.DRIVE_STATUS)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setColor(ContextCompat.getColor(context, R.color.brand_blue))
            .setContentTitle(context.getString(R.string.auto_stopped_title))
            .setContentText(context.getString(R.string.auto_stopped_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.auto_stopped_text)))
            .setAutoCancel(true)
            .setContentIntent(NotificationIntents.openApp(context))
            .build()
        manager.notify(ID_AUTO_STOPPED, notification)
    }

    private fun canNotify(): Boolean =
        LocationPermissions.hasNotificationPermission(context) && manager.areNotificationsEnabled()

    companion object {
        const val ID_SESSION = 1001
        const val ID_CAMERA_ALERT = 2001
        const val ID_DRIVE_END_CHECK = 2002
        const val ID_AUTO_STOPPED = 2003
        const val ALERT_TIMEOUT_MS = 25_000L
    }
}
