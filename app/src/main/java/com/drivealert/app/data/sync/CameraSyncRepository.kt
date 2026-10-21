package com.drivealert.app.data.sync

import com.drivealert.app.cameras.CameraRepository
import com.drivealert.app.database.CameraDao
import com.drivealert.app.database.DataPackageDao
import com.drivealert.app.database.DataPackageEntity
import com.drivealert.app.network.ApiConfig
import com.drivealert.app.network.CameraDataApi
import com.drivealert.app.network.CameraRecordValidator
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

sealed interface SyncResult {
    data object NotConfigured : SyncResult
    data class Success(val packagesUpdated: Int, val camerasChanged: Int) : SyncResult
    data class Failure(val retryable: Boolean, val message: String) : SyncResult
}

/**
 * Downloads city/state camera packages and applies incremental deltas.
 * Only package ids and data versions are sent to the server.
 */
@Singleton
class CameraSyncRepository @Inject constructor(
    private val api: CameraDataApi,
    private val packageDao: DataPackageDao,
    private val cameraDao: CameraDao,
    private val cameraRepository: CameraRepository,
) {
    fun observePackages(): Flow<List<DataPackageEntity>> = packageDao.observeAll()

    fun observeLastUpdated(): Flow<Long?> = packageDao.observeLastUpdated()

    suspend fun refreshCatalog(): SyncResult = runSync {
        val remote = api.packages()
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
        SyncResult.Success(packagesUpdated = 0, camerasChanged = 0)
    }

    suspend fun syncInstalledPackages(nowMillis: Long = System.currentTimeMillis()): SyncResult = runSync {
        var packages = 0
        var changed = 0
        for (pkg in packageDao.installed().filterNot { it.isTestData }) {
            val delta = api.packageDelta(pkg.id, pkg.dataVersion)
            if (delta.packageId != pkg.id || delta.toVersion < pkg.dataVersion) continue
            if (delta.toVersion == pkg.dataVersion && delta.upserts.isEmpty() && delta.removedIds.isEmpty()) continue
            val cameras = delta.upserts.mapNotNull { CameraRecordValidator.toCameraOrNull(it, pkg.id) }
            cameraRepository.applyDelta(pkg.id, delta.replaceAll, cameras, delta.removedIds)
            packageDao.upsert(
                pkg.copy(
                    dataVersion = delta.toVersion,
                    cameraCount = cameraDao.countInPackage(pkg.id),
                    lastUpdatedAt = nowMillis,
                ),
            )
            packages++
            changed += cameras.size + delta.removedIds.size
        }
        cameraRepository.purgeExpired(nowMillis)
        SyncResult.Success(packages, changed)
    }

    suspend fun setPackageInstalled(packageId: String, installed: Boolean) {
        packageDao.setInstalled(packageId, installed)
        if (!installed) {
            cameraRepository.applyDelta(packageId, replaceAll = true, upserts = emptyList(), removedIds = emptyList())
            packageDao.getById(packageId)?.let {
                packageDao.upsert(it.copy(dataVersion = 0L, cameraCount = 0, lastUpdatedAt = null))
            }
        }
    }

    private suspend fun runSync(block: suspend () -> SyncResult): SyncResult {
        if (!ApiConfig.isConfigured) return SyncResult.NotConfigured
        return try {
            block()
        } catch (e: IOException) {
            SyncResult.Failure(retryable = true, message = "Network error")
        } catch (e: HttpException) {
            SyncResult.Failure(retryable = e.code() >= 500 || e.code() == 429, message = "HTTP ${e.code()}")
        } catch (e: SerializationException) {
            SyncResult.Failure(retryable = false, message = "Unexpected server response")
        }
    }
}
