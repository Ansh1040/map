package com.drivealert.app.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.drivealert.app.MainActivity
import com.drivealert.app.domain.model.StopReason
import com.drivealert.app.location.service.CameraAlertService

object NotificationIntents {
    private const val REQUEST_OPEN_APP = 10
    private const val REQUEST_STOP = 11
    private const val REQUEST_END_DRIVE = 12
    private const val REQUEST_KEEP_ALERTS = 13

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_APP,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        FLAGS,
    )

    fun stopAlerts(context: Context): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_STOP,
        CameraAlertService.stopIntent(context, StopReason.USER_NOTIFICATION),
        FLAGS,
    )

    fun endDrive(context: Context): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_END_DRIVE,
        CameraAlertService.stopIntent(context, StopReason.USER_CONFIRMED_DRIVE_ENDED),
        FLAGS,
    )

    fun keepAlerts(context: Context): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_KEEP_ALERTS,
        CameraAlertService.keepAlertsIntent(context),
        FLAGS,
    )
}
