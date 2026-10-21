package com.drivealert.app.location.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.di.BindingsModule
import com.drivealert.app.di.StorageModule
import com.drivealert.app.domain.model.StopReason
import com.drivealert.app.domain.ports.AlertHistoryRecorder
import com.drivealert.app.domain.ports.AlertPresenter
import com.drivealert.app.domain.ports.Clock
import com.drivealert.app.domain.ports.LocationSource
import com.drivealert.app.domain.session.DrivingSessionManager
import com.drivealert.app.testutil.FakeCameraRepository
import com.drivealert.app.testutil.FakeLocationSource
import com.drivealert.app.testutil.RecordingHistory
import com.drivealert.app.testutil.RecordingPresenter
import com.drivealert.app.util.SystemClockImpl
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Foreground-service lifecycle with the location stack replaced by fakes.
 * Real-device behaviour (Google Maps in front, screen locked) is covered by
 * the instrumented test and docs/14-oneplus13-device-test-checklist.md.
 */
@HiltAndroidTest
@UninstallModules(BindingsModule::class, StorageModule::class)
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class)
class CameraAlertServiceTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private val fakeSource = FakeLocationSource()

    @BindValue @JvmField val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { File.createTempFile("settings-", ".preferences_pb").also { it.delete() } },
    )

    @BindValue @JvmField val locationSource: LocationSource = fakeSource
    @BindValue @JvmField val presenter: AlertPresenter = RecordingPresenter()
    @BindValue @JvmField val history: AlertHistoryRecorder = RecordingHistory()
    @BindValue @JvmField val cameraRepository: CameraRepository = FakeCameraRepository()
    @BindValue @JvmField val clock: Clock = SystemClockImpl()

    @Inject lateinit var sessionManager: DrivingSessionManager

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    private fun grantLocation() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private fun intent(action: String?) = Intent(app, CameraAlertService::class.java).apply { action?.let { setAction(it) } }

    @Test
    fun startRunsAsForegroundServiceAndStopEndsEverything() {
        grantLocation()
        val service = Robolectric.buildService(CameraAlertService::class.java).create().get()

        val result = service.onStartCommand(intent(CameraAlertService.ACTION_START), 0, 1)
        assertEquals("must never be restarted silently", Service.START_NOT_STICKY, result)
        val shadow = shadowOf(service)
        val notification = shadow.lastForegroundNotification
        assertEquals("Camera alerts active", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(sessionManager.isRunning)

        service.onStartCommand(CameraAlertService.stopIntent(app, StopReason.USER_NOTIFICATION), 0, 2)
        assertTrue(shadow.isStoppedBySelf)
        assertTrue(shadow.isForegroundStopped)
        assertFalse(sessionManager.isRunning)
        assertTrue("location callbacks removed", fakeSource.stopCount >= 1)
        assertEquals(StopReason.USER_NOTIFICATION, sessionManager.lastStopReason)
    }

    @Test
    fun systemRecreationWithNullIntentDoesNotResumeTracking() {
        grantLocation()
        val service = Robolectric.buildService(CameraAlertService::class.java).create().get()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(sessionManager.isRunning)
        assertTrue(fakeSource.appliedPlans.isEmpty())
    }

    @Test
    fun refusesToStartWithoutPreciseLocation() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val service = Robolectric.buildService(CameraAlertService::class.java).create().get()
        service.onStartCommand(intent(CameraAlertService.ACTION_START), 0, 1)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(sessionManager.isRunning)
    }

    @Test
    fun destroyingTheServiceStopsTheSession() {
        grantLocation()
        val controller = Robolectric.buildService(CameraAlertService::class.java).create()
        controller.get().onStartCommand(intent(CameraAlertService.ACTION_START), 0, 1)
        assertTrue(sessionManager.isRunning)
        controller.destroy()
        assertFalse(sessionManager.isRunning)
        assertEquals(StopReason.SERVICE_DESTROYED, sessionManager.lastStopReason)
    }
}
