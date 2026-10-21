package com.drivealert.app.reporting

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drivealert.app.database.CameraReportEntity
import com.drivealert.app.database.DriveAlertDatabase
import com.drivealert.app.domain.model.DataSource
import com.drivealert.app.domain.reporting.ReportDraft
import com.drivealert.app.domain.reporting.ReportModerationPolicy
import com.drivealert.app.domain.reporting.ReportStatus
import com.drivealert.app.domain.reporting.ReportType
import com.drivealert.app.network.CameraDataApi
import com.drivealert.app.network.HttpStatusException
import com.drivealert.app.network.OsmNotesApi
import com.drivealert.app.network.PackageDeltaDto
import com.drivealert.app.network.PackageDto
import com.drivealert.app.network.ReportRequestDto
import com.drivealert.app.network.ReportResponseDto
import com.drivealert.app.testutil.FakeCameraRepository
import com.drivealert.app.testutil.Fixtures
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Consent, privacy and retry guarantees for community reports (OpenStreetMap mode). */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ReportRepositoryTest {

    private class RecordingNotes : OsmNotesApi {
        data class Note(val lat: Double, val lon: Double, val text: String)
        val created = ArrayList<Note>()
        var failNextAfterCreate = false
        var failWith: Exception? = null
        var searchFailWith: Exception? = null
        var returnId: Long? = 77L

        override suspend fun createNote(latitude: Double, longitude: Double, text: String): Long? {
            failWith?.let { throw it }
            created += Note(latitude, longitude, text)
            if (failNextAfterCreate) {
                failNextAfterCreate = false
                throw IOException("response lost")
            }
            return returnId
        }

        override suspend fun findNoteByToken(token: String): Long? {
            searchFailWith?.let { throw it }
            return if (created.any { it.text.contains(token) }) 77L else null
        }
    }

    private val noServer = object : CameraDataApi {
        override suspend fun packages(): List<PackageDto> = error("no server in OSM mode")
        override suspend fun packageDelta(packageId: String, sinceVersion: Long): PackageDeltaDto = error("no server")
        override suspend fun submitReport(report: ReportRequestDto): ReportResponseDto = error("no server")
    }

    private lateinit var db: DriveAlertDatabase
    private lateinit var notes: RecordingNotes
    private lateinit var repository: ReportRepository
    private var scheduled = 0
    private var clock = 1_800_000_000_000L

    private val osmCamera = Fixtures.camera("osm-node-123", Fixtures.point(5_000.0)).copy(dataSource = DataSource.OPENSTREETMAP, isTestData = false)
    private val testCamera = Fixtures.camera("TEST-001", Fixtures.point(9_000.0))

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DriveAlertDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notes = RecordingNotes()
        repository = ReportRepository(
            reportDao = db.reportDao(),
            cameraRepository = FakeCameraRepository(listOf(osmCamera, testCamera)),
            policy = ReportModerationPolicy(),
            scheduler = { scheduled++ },
            api = noServer,
            notes = notes,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun next(): Long {
        clock += 10 * 60_000L // stay clear of the rate limiter
        return clock
    }

    private val here = Fixtures.point(0.0)
    private val precise = ReportPosition(accuracyM = 8f, isMock = false)

    private suspend fun newCamera(publish: Boolean, position: ReportPosition? = precise, at: com.drivealert.app.domain.geo.GeoPoint = here) =
        repository.submit(
            ReportDraft(ReportType.NEW_CAMERA, latitude = at.latitude, longitude = at.longitude),
            publishToOpenStreetMap = publish,
            position = position,
            nowMillis = next(),
        ) as SubmitResult.Queued

    @Test
    fun withoutConsentNothingIsPublished() = runTest {
        val result = newCamera(publish = false)
        assertEquals(ReportDestination.DEVICE_ONLY, result.destination)
        assertEquals(0, scheduled)
        repository.uploadPending(next())
        assertTrue(notes.created.isEmpty())
        assertEquals(ReportStatus.SAVED_ON_DEVICE.name, db.reportDao().getById(result.localId)!!.status)
    }

    @Test
    fun consentedPreciseReportIsPublishedAtTheReportedPosition() = runTest {
        val result = newCamera(publish = true)
        assertEquals(ReportDestination.OPENSTREETMAP_NOTE, result.destination)
        assertEquals(1, scheduled)
        assertEquals(UploadResult.DONE, repository.uploadPending(next()))
        val note = notes.created.single()
        assertEquals(here.latitude, note.lat, 1e-9)
        assertEquals(here.longitude, note.lon, 1e-9)
        val row = db.reportDao().getById(result.localId)!!
        assertEquals(ReportStatus.UNDER_REVIEW.name, row.status)
        assertEquals("osm-note-77", row.remoteId)
    }

    @Test
    fun reportsAboutAnExistingCameraArePlacedAtTheCameraNotTheReporter() = runTest {
        repository.submit(ReportDraft(ReportType.CAMERA_REMOVED, cameraId = osmCamera.id), publishToOpenStreetMap = true, nowMillis = next())
        repository.uploadPending(next())
        val note = notes.created.single()
        assertEquals(osmCamera.latitude, note.lat, 1e-9)
        assertEquals(osmCamera.longitude, note.lon, 1e-9)
        assertTrue(note.text.contains("https://www.openstreetmap.org/node/123"))
    }

    @Test
    fun wrongPositionReportFarFromTheCameraIsKeptOnThePhone() = runTest {
        val result = repository.submit(
            ReportDraft(ReportType.INCORRECT_POSITION, cameraId = osmCamera.id, latitude = here.latitude, longitude = here.longitude),
            publishToOpenStreetMap = true,
            position = precise,
            nowMillis = next(),
        ) as SubmitResult.Queued
        assertEquals(ReportDestination.DEVICE_ONLY, result.destination)
        assertEquals(KeptOnDeviceReason.FAR_FROM_CAMERA, result.keptReason)
    }

    @Test
    fun imprecisePositionsMockPositionsAndTestCamerasAreNeverPublished() = runTest {
        assertEquals(KeptOnDeviceReason.LOW_ACCURACY, newCamera(true, ReportPosition(120f, false)).keptReason)
        assertEquals(KeptOnDeviceReason.LOW_ACCURACY, newCamera(true, null, Fixtures.point(1_000.0)).keptReason)
        assertEquals(KeptOnDeviceReason.MOCK_LOCATION, newCamera(true, ReportPosition(5f, true), Fixtures.point(2_000.0)).keptReason)
        val test = repository.submit(ReportDraft(ReportType.CAMERA_REMOVED, cameraId = testCamera.id), publishToOpenStreetMap = true, nowMillis = next()) as SubmitResult.Queued
        assertEquals(KeptOnDeviceReason.TEST_DATA, test.keptReason)
        repository.uploadPending(next())
        assertTrue(notes.created.isEmpty())
    }

    @Test
    fun legacyReportsWithoutConsentAreNeverPublished() = runTest {
        // Saved by the previous version, which queued every report as PENDING_UPLOAD.
        val legacyId = db.reportDao().insert(
            CameraReportEntity(
                remoteId = null, reportType = ReportType.NEW_CAMERA.name, cameraId = null,
                latitude = 12.0, longitude = 77.0, bearingDeg = null, speedLimitKmh = null, cameraType = null,
                note = null, createdAt = clock, status = ReportStatus.PENDING_UPLOAD.name, expiresAt = clock + 10L * 24 * 3_600_000L, lastError = null,
            ),
        )
        repository.reconcileOnStart(next())
        assertEquals(ReportStatus.SAVED_ON_DEVICE.name, db.reportDao().getById(legacyId)!!.status)

        newCamera(publish = true)
        repository.uploadPending(next())
        assertEquals(1, notes.created.size)
        assertTrue(notes.created.none { it.lat == 12.0 && it.lon == 77.0 })
    }

    @Test
    fun aNoteWhoseResponseWasLostIsNotPostedTwice() = runTest {
        newCamera(publish = true)
        notes.failNextAfterCreate = true
        assertEquals(UploadResult.RETRY, repository.uploadPending(next()))
        assertEquals(UploadResult.DONE, repository.uploadPending(next()))
        assertEquals(1, notes.created.size)
    }

    @Test
    fun aFailedSearchForALostNoteIsRetriedNeverRejectedOrReposted() = runTest {
        val result = newCamera(publish = true)
        notes.failNextAfterCreate = true
        assertEquals(UploadResult.RETRY, repository.uploadPending(next()))
        // Whether the note exists is unknown while the search fails, whatever the error.
        val searchFailures = listOf(
            HttpStatusException(404),
            HttpStatusException(503),
            IOException("offline"),
            SerializationException("not JSON"),
            IllegalArgumentException("not a JSON object"),
        )
        for (failure in searchFailures) {
            notes.searchFailWith = failure
            assertEquals(UploadResult.RETRY, repository.uploadPending(next()))
            assertEquals(ReportStatus.PENDING_OSM_NOTE.name, db.reportDao().getById(result.localId)!!.status)
        }
        notes.searchFailWith = null
        assertEquals(UploadResult.DONE, repository.uploadPending(next()))
        assertEquals(1, notes.created.size)
        assertEquals(ReportStatus.UNDER_REVIEW.name, db.reportDao().getById(result.localId)!!.status)
    }

    @Test
    fun acceptedNoteWithUnreadableIdCountsAsPublished() = runTest {
        val result = newCamera(publish = true)
        notes.returnId = null
        repository.uploadPending(next())
        repository.uploadPending(next())
        assertEquals(1, notes.created.size)
        assertEquals("osm-note-unknown", db.reportDao().getById(result.localId)!!.remoteId)
    }

    @Test
    fun clientErrorsAreNotRetriedForever() = runTest {
        val result = newCamera(publish = true)
        notes.failWith = HttpStatusException(400)
        assertEquals(UploadResult.DONE, repository.uploadPending(next()))
        assertEquals(ReportStatus.REJECTED.name, db.reportDao().getById(result.localId)!!.status)
        notes.failWith = HttpStatusException(503)
        newCamera(publish = true, at = Fixtures.point(3_000.0))
        assertEquals(UploadResult.RETRY, repository.uploadPending(next()))
    }
}
