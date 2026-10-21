package com.drivealert.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.drivealert.app.R

object NotificationChannels {
    /** Persistent "Camera alerts active" notification. Low importance: no sound, no peeking. */
    const val SESSION = "session_status"

    /**
     * Camera warnings. High importance so they appear as heads-up
     * notifications over Google Maps. The channel itself is silent: the app
     * plays its own chime/voice on the navigation-guidance audio stream so
     * they duck music correctly and respect the user's sound settings.
     */
    const val CAMERA_ALERTS = "camera_alerts"

    /** "Has your drive ended?" and "alerts stopped" messages. */
    const val DRIVE_STATUS = "drive_status"

    fun createAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val session = NotificationChannel(
            SESSION,
            context.getString(R.string.channel_session_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_session_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }

        val alerts = NotificationChannel(
            CAMERA_ALERTS,
            context.getString(R.string.channel_alerts_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alerts_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        val status = NotificationChannel(
            DRIVE_STATUS,
            context.getString(R.string.channel_status_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_status_description)
            setSound(null, null)
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(session, alerts, status))
    }
}
