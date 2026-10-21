package com.drivealert.app.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drivealert.app.alerts.AlertTextFormatter
import com.drivealert.app.domain.model.CameraAlert
import com.drivealert.app.domain.model.CameraType
import com.drivealert.app.domain.model.GpsStatus
import com.drivealert.app.domain.model.NextCamera
import com.drivealert.app.domain.model.SessionState
import com.drivealert.app.domain.model.TrackingMode
import com.drivealert.app.domain.model.VerificationStatus
import com.drivealert.app.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NotificationsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val formatter = AlertTextFormatter(context)
    private val notifier = CameraAlertNotifier(context, formatter)

    private val camera = Fixtures.camera("cam", Fixtures.point(500.0), type = CameraType.FIXED_SPEED, speedLimitKmh = 60)
    private val alert = CameraAlert(camera, 500, 500, 48, isOverSpeedLimit = false, isFirstForCamera = true, isEstimatedPosition = false, timestampMillis = 0L)

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannels.createAll(context)
    }

    @Test
    fun `camera warnings use a high-importance silent channel`() {
        val alerts = manager.getNotificationChannel(NotificationChannels.CAMERA_ALERTS)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alerts.importance)
        assertNull(alerts.sound)
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(NotificationChannels.SESSION).importance)
    }

    @Test
    fun `alert notification shows the documented content`() {
        val n = notifier.buildCameraAlert(alert)
        assertEquals("Speed camera ahead", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("500 m • Speed limit 60 km/h", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(big, big.contains("Current speed 48 km/h — Drive safely"))
        assertEquals(NotificationChannels.CAMERA_ALERTS, n.channelId)
        assertEquals(NotificationCompat.CATEGORY_NAVIGATION, n.category)
    }

    @Test
    fun `alert never uses a full-screen interruption and is not silenced`() {
        val n = notifier.buildCameraAlert(alert)
        assertNull(n.fullScreenIntent)
        // A "silent" group would suppress the heads-up display.
        assertNotEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        assertTrue(n.timeoutAfter > 0)
    }

    @Test
    fun `over the limit asks the driver to slow down`() {
        val n = notifier.buildCameraAlert(alert.copy(speedKmh = 72, isOverSpeedLimit = true))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Current speed 72 km/h — Please slow down"))
    }

    @Test
    fun `unverified reports are labelled`() {
        val pending = alert.copy(camera = camera.copy(verificationStatus = VerificationStatus.PENDING_VERIFICATION))
        assertTrue(formatter.detail(pending).contains("unverified"))
        assertTrue(formatter.voicePhrase(pending).contains("unverified"))
    }

    @Test
    fun `posting an alert reaches the notification manager`() {
        notifier.showCameraAlert(alert)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        notifier.cancelCameraAlert()
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `voice phrases`() {
        assertEquals("Speed camera ahead in 500 metres. Speed limit 60.", formatter.voicePhrase(alert))
        assertEquals("Camera in 200 metres.", formatter.voicePhrase(alert.copy(distanceM = 200, isFirstForCamera = false)))
        assertEquals("1.2 km", formatter.distanceLabel(1_200))
    }

    @Test
    fun `persistent session notification is ongoing with a stop action`() {
        val factory = SessionNotificationFactory(context, formatter)
        val state = SessionState.Active(
            sessionId = "s", startedAtMillis = 0L, trackingMode = TrackingMode.CLOSE, gpsStatus = GpsStatus.GOOD,
            accuracyM = 5f, speedKmh = 50, nearbyCameraCount = 2, nextCamera = NextCamera(camera, 800),
            lastAlert = null, passedCameraCount = 0, isStationary = false, awaitingDriveEndConfirmation = false,
        )
        val n = factory.build(state)
        assertEquals("Camera alerts active", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Stop alerts", n.actions.single().title.toString())
        assertEquals("Speed camera ahead • 800 m", factory.summary(state))
        assertEquals("2 cameras within 5 km • power-saving tracking", factory.summary(state.copy(trackingMode = TrackingMode.PASSIVE)))
    }
}
