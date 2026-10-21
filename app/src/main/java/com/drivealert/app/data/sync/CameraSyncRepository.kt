package com.drivealert.app.data.sync

import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.data.osm.OsmCameraParser
import com.drivealert.app.data.osm.OsmSnapshotImporter
import com.drivealert.app.database.CameraDao
import com.drivealert.app.database.DataPackageDao
import com.drivealert.app.database.DataPackageEntity
import com.drivealert.app.network.ApiConfig
import com.drivealert.app.network.CameraDataApi
import com.drivealert.app.network.CameraRecordValidator
import com.drivealert.app.network.HttpStatusException
import com.drivealert.app.network.OpenDataClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

sealed interface SyncResult {
    data object NotConfigured : SyncResult
    data class Success(val packagesUpdated: Int, val camerasChanged: Int) : SyncResult
    data class Failure(val retryable: Boolean, val message: String) : SyncResult
}

/**
 * Keeps the local camera packages up to date.
 *
 * Default (open data): packages are Indian states / UTs from OpenStreetMap.
 * The APK bundles a snapshot; updates download one weekly-published snapshot
 * file — no location or device data is sent.
 *
 * Optional: if CAMERA_API_BASE_URL is configured, the licensed-server
 * contract in docs/server-api-contract.md is used instead (incremental deltas).
 */
@Singleton
class CameraSyncRepository @Inject constructor(
    private val api: CameraDataApi,
    private val openData: OpenDataClient,
    private val osmImporter: OsmSnapshotImporter,
    private val packageDao: DataPackageDao,
    private val cameraDao: CameraDao,
    private val cameraRepository: CameraRepository,
) {
    /** Server mode: user switches and sync writes to a package never interleave. */
    private val serverPackageMutex = Mutex()

    fun observePackages(): Flow<List<DataPackageEntity>> = packageDao.observeAll()

    fun observeLastUpdated(): Flow<Long?> = packageDao.observeLastUpdated()

    val usesOpenStreetMap: Boolean get() = !ApiConfig.isConfigured

    suspend fun refreshCatalog(): SyncResult = runSync {
        if (usesOpenStreetMap) {
            val result = osmImporter.importBundledIfNeeded()
            return@runSync SyncResult.Success(result.regionsUpdated, result.camerasImported)
        }
        val remote = api.packages()
        serverPackageMutex.withLock {
            val existing = remote.mapNotNull { packageDao.getById(it.id) }.associateBy { it.id }
            packageDao.upsertAll(
                remote.map { dto ->
                    val local = existing[dto.id]
                    DataPackageEntity(
                        id = dto.id,
                        name = dto.name,
                        state = dto.state,
                        city = dto.city,
                        dataVersion = local?.dataVersion ?: 0L,
                        cameraCount = local?.cameraCount ?: 0,
                        lastUpdatedAt = local?.lastUpdatedAt,
                        isInstalled = local?.isInstalled ?: false,
                        coverageNote = dto.coverageNote,
                        isTestData = false,
                    )
                },
            )
        }
        SyncResult.Success(packagesUpdated = 0, camerasChanged = 0)
    }

    suspend fun syncInstalledPackages(nowMillis: Long = System.currentTimeMillis()): SyncResult = runSync {
        if (usesOpenStreetMap) {
            val text = openData.fetchSnapshot()
            val result = osmImporter.importSnapshotText(text, nowMillis)
            cameraRepository.purgeExpired(nowMillis)
            return@runSync SyncResult.Success(result.regionsUpdated, result.camerasImported)
        }
        var packages = 0
        var changed = 0
        for (pkg in packageDao.installed().filterNot { it.isTestData }) {
            val delta = api.packageDelta(pkg.id, pkg.dataVersion)
            if (delta.packageId != pkg.id || delta.toVersion < pkg.dataVersion) continue
            if (delta.toVersion == pkg.dataVersion && delta.upserts.isEmpty() && delta.removedIds.isEmpty()) continue
            val cameras = delta.upserts.mapNotNull { CameraRecordValidator.toCameraOrNull(it, pkg.id) }
            val applied = serverPackageMutex.withLock {
                // Re-read after the download: the user may have switched the package off meanwhile
                // (or off and on, which resets it), and a delta only fits the version it was asked for.
                val current = packageDao.getById(pkg.id)
                if (current == null || !current.isInstalled || current.dataVersion != pkg.dataVersion) return@withLock false
                cameraRepository.applyDelta(pkg.id, delta.replaceAll, cameras, delta.removedIds)
                packageDao.upsert(
                    current.copy(
                        dataVersion = delta.toVersion,
                        cameraCount = cameraDao.countInPackage(pkg.id),
                        lastUpdatedAt = nowMillis,
                    ),
                )
                true
            }
            if (applied) {
                packages++
                changed += cameras.size + delta.removedIds.size
            }
        }
        cameraRepository.purgeExpired(nowMillis)
        SyncResult.Success(packages, changed)
    }

    /** Offline-first: load the snapshot shipped in the APK. Never touches the network. */
    suspend fun importBundledOpenData() {
        if (usesOpenStreetMap) osmImporter.importBundledIfNeeded()
    }

    suspend fun setPackageInstalled(packageId: String, installed: Boolean) {
        val region = OsmCameraParser.regionCodeOf(packageId)
        if (region != null) {
            // Same lock as imports, so a running update cannot undo the user's choice.
            osmImporter.setRegionInstalled(region, installed)
            return
        }
        // Server-mode package: the constrained sync worker downloads it after enabling.
        // Same lock as the sync's per-package apply, so a running sync cannot undo the choice.
        serverPackageMutex.withLock {
            packageDao.setInstalled(packageId, installed)
            if (!installed) {
                cameraRepository.applyDelta(packageId, replaceAll = true, upserts = emptyList(), removedIds = emptyList())
                packageDao.getById(packageId)?.let {
                    packageDao.upsert(it.copy(dataVersion = 0L, cameraCount = 0, lastUpdatedAt = null))
                }
            }
        }
    }

    private suspend fun runSync(block: suspend () -> SyncResult): SyncResult = try {
        block()
    } catch (e: HttpStatusException) {
        SyncResult.Failure(retryable = e.code >= 500 || e.code == 429, message = "HTTP ${e.code}")
    } catch (e: HttpException) {
        SyncResult.Failure(retryable = e.code() >= 500 || e.code() == 429, message = "HTTP ${e.code()}")
    } catch (e: IOException) {
        SyncResult.Failure(retryable = true, message = "Network error")
    } catch (e: SerializationException) {
        SyncResult.Failure(retryable = false, message = "Unexpected data format")
    } catch (e: IllegalArgumentException) {
        SyncResult.Failure(retryable = false, message = "Invalid data")
    }
}
