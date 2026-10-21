package com.drivealert.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.drivealert.app.domain.model.StopReason
import com.drivealert.app.location.service.CameraAlertService
import com.drivealert.app.notifications.CameraAlertNotifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real foreground-service start/stop on a device. Run with:
 *   ./gradlew connectedDebugAndroidTest
 * Manual scenarios (Google Maps in front, locked screen, Power Saving Mode)
 * are in docs/14-oneplus13-device-test-checklist.md.
 */
@RunWith(AndroidJUnit4::class)
class CameraAlertServiceInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = context.getSystemService(NotificationManager::class.java)

    private fun sessionNotificationVisible(): Boolean =
        notifications.activeNotifications.any { it.id == CameraAlertNotifier.ID_SESSION }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun startShowsPersistentNotificationAndStopRemovesIt() {
        CameraAlertService.start(context)
        assertTrue("\"Camera alerts active\" notification not shown", waitFor { sessionNotificationVisible() })

        CameraAlertService.stop(context, StopReason.USER_IN_APP)
        assertTrue("session notification still visible after stop", waitFor { !sessionNotificationVisible() })
    }

    @Test
    fun serviceDoesNotRestartAfterStop() {
        CameraAlertService.start(context)
        waitFor { sessionNotificationVisible() }
        CameraAlertService.stop(context, StopReason.USER_IN_APP)
        waitFor { !sessionNotificationVisible() }
        Thread.sleep(3_000)
        assertFalse(sessionNotificationVisible())
    }
}
