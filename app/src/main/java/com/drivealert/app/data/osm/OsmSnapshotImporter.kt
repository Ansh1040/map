package com.drivealert.app.data.osm

import android.content.Context
import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.database.DataPackageDao
import com.drivealert.app.database.DataPackageEntity
import com.drivealert.app.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads OpenStreetMap camera snapshots into the local database, one data
 * package per state / union territory.
 *
 * Every write (bundled import, downloaded update, user switching a package
 * on/off) runs under one mutex, so they cannot overwrite each other. Rules:
 * - a package is only replaced by a newer snapshot, except that a new
 *   [PARSER_VERSION] re-applies the bundled snapshot to every installed package;
 * - switched-off packages are not filled;
 * - an update that would empty a region which had cameras is ignored
 *   (protects against a truncated snapshot).
 */
@Singleton
class OsmSnapshotImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val packageDao: DataPackageDao,
    private val cameraRepository: CameraRepository,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    @Volatile
    private var bundledCache: OsmSnapshot? = null

    data class ImportResult(val regionsUpdated: Int, val camerasImported: Int)

    /**
     * First launch / app update: import the snapshot bundled in the APK. Cheap no-op otherwise.
     * If the stored cameras were imported with another [PARSER_VERSION], every installed
     * package is re-parsed from the bundled snapshot, even when its data is not older.
     */
    suspend fun importBundledIfNeeded(nowMillis: Long = System.currentTimeMillis()): ImportResult = mutex.withLock {
        val text = readBundled()
        val version = headerVersion(text)
        val parserChanged = settings.appliedOsmParserVersion() != PARSER_VERSION
        if (!parserChanged && version != null && version == settings.importedBundledSnapshot()) return@withLock ImportResult(0, 0)
        val snapshot = parse(text, nowMillis).also { bundledCache = it }
        val result = importLocked(snapshot, replaceInstalled = parserChanged)
        settings.setAppliedOsmParserVersion(PARSER_VERSION)
        version?.let { settings.setImportedBundledSnapshot(it) }
        result
    }

    /** A downloaded snapshot (same format as the bundled one). */
    suspend fun importSnapshotText(text: String, nowMillis: Long = System.currentTimeMillis()): ImportResult =
        mutex.withLock { importLocked(parse(text, nowMillis)) }

    /**
     * The user switched a package on or off. Switching on fills it from the
     * bundled snapshot immediately (works offline); the next sync brings newer data.
     */
    suspend fun setRegionInstalled(regionCode: String, installed: Boolean, nowMillis: Long = System.currentTimeMillis()) = mutex.withLock {
        val packageId = OsmCameraParser.packageId(regionCode)
        packageDao.setInstalled(packageId, installed)
        if (installed) {
            val snapshot = bundled(nowMillis)
            val cameras = snapshot.camerasByRegion[regionCode].orEmpty()
            cameraRepository.applyDelta(packageId, replaceAll = true, upserts = cameras, removedIds = emptyList())
            packageDao.getById(packageId)?.let {
                packageDao.upsert(it.copy(isInstalled = true, dataVersion = snapshot.osmBaseMillis ?: 0L, cameraCount = cameras.size, lastUpdatedAt = snapshot.osmBaseMillis))
            }
        } else {
            cameraRepository.applyDelta(packageId, replaceAll = true, upserts = emptyList(), removedIds = emptyList())
            packageDao.getById(packageId)?.let {
                packageDao.upsert(it.copy(isInstalled = false, dataVersion = 0L, cameraCount = 0, lastUpdatedAt = null))
            }
        }
    }

    /** @param replaceInstalled also replace installed packages whose data is not older (parser changed). */
    private suspend fun importLocked(snapshot: OsmSnapshot, replaceInstalled: Boolean = false): ImportResult {
        val version = snapshot.osmBaseMillis ?: return ImportResult(0, 0)
        var regions = 0
        var cameras = 0
        for (region in snapshot.regions) {
            val packageId = OsmCameraParser.packageId(region.code)
            val existing = packageDao.getById(packageId)
            val forceReplace = replaceInstalled && existing?.isInstalled == true
            if (existing != null && existing.dataVersion >= version && !forceReplace) continue
            val regionCameras = snapshot.camerasByRegion[region.code].orEmpty()
            val installed = existing?.isInstalled ?: true
            if (installed && regionCameras.isEmpty() && (existing?.cameraCount ?: 0) >= MIN_CAMERAS_TO_PROTECT) {
                // A region suddenly losing every camera is almost certainly a truncated download.
                continue
            }
            if (installed) {
                cameraRepository.applyDelta(packageId, replaceAll = true, upserts = regionCameras, removedIds = emptyList())
                cameras += regionCameras.size
            }
            packageDao.upsert(
                DataPackageEntity(
                    id = packageId,
                    name = region.name,
                    state = region.name,
                    city = null,
                    dataVersion = if (installed) version else 0L,
                    cameraCount = if (installed) regionCameras.size else 0,
                    lastUpdatedAt = if (installed) version else null,
                    isInstalled = installed,
                    coverageNote = OsmCameraParser.ATTRIBUTION,
                    isTestData = false,
                ),
            )
            regions++
        }
        return ImportResult(regions, cameras)
    }

    private suspend fun bundled(nowMillis: Long): OsmSnapshot =
        bundledCache ?: parse(readBundled(), nowMillis).also { bundledCache = it }

    private suspend fun parse(text: String, nowMillis: Long): OsmSnapshot =
        withContext(Dispatchers.Default) { OsmCameraParser.parse(text, nowMillis) }

    private suspend fun readBundled(): String = withContext(Dispatchers.IO) {
        context.assets.open(OsmConfig.BUNDLED_ASSET).bufferedReader().use { it.readText() }
    }

    companion object {
        /**
         * Version of what [OsmCameraParser] makes of a snapshot. Bump it whenever parsing
         * changes, so cameras imported by an older build are re-parsed on the next start.
         * 2: `direction` is the way the camera faces (incl. negative degrees); noisy
         * relation geometry is ignored.
         */
        const val PARSER_VERSION = 2

        private const val MIN_CAMERAS_TO_PROTECT = 5
        private val HEADER = Regex("\"osm_base\"\\s*:\\s*\"([^\"]+)\"")

        /** The snapshot builder writes `osm_base` before `elements`, so the header suffices. */
        private fun headerVersion(text: String): String? = HEADER.find(text.take(2_000))?.groupValues?.get(1)
    }
}
