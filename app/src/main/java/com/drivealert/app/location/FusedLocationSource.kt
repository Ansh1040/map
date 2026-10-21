package com.drivealert.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.model.LocationPlan
import com.drivealert.app.domain.model.LocationRequestSpec
import com.drivealert.app.domain.ports.LocationSource
import com.drivealert.app.permissions.LocationPermissions
import com.drivealert.app.util.AppLog
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fused Location Provider implementation of [LocationSource].
 *
 * Two independent callbacks are used: one PRIORITY_PASSIVE request that only
 * receives fixes other apps (e.g. a navigation app) already paid for, and one
 * active request whose priority/interval follows the tracking mode. Callbacks
 * run on a dedicated background looper so the main thread is never woken.
 */
@Singleton
class FusedLocationSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LocationSource {

    private val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    private val _fixes = MutableSharedFlow<LocationFix>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: Flow<LocationFix> = _fixes.asSharedFlow()

    private var thread: HandlerThread? = null
    private var appliedPassive: LocationRequestSpec? = null
    private var appliedActive: LocationRequestSpec? = null

    @Volatile
    private var lastEmittedElapsedMs = 0L

    private val passiveCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) = emitAll(result)
    }

    private val activeCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) = emitAll(result)
    }

    private fun emitAll(result: LocationResult) {
        for (location in result.locations) {
            val fix = location.toFix()
            // The passive callback also receives this app's own active fixes: de-duplicate.
            if (fix.elapsedRealtimeMillis <= lastEmittedElapsedMs) continue
            lastEmittedElapsedMs = fix.elapsedRealtimeMillis
            _fixes.tryEmit(fix)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun lastKnownLocation(maxAgeMs: Long): LocationFix? {
        if (!LocationPermissions.hasAnyLocation(context)) return null
        return try {
            val location = client.lastLocation.awaitResult() ?: return null
            if (location.ageMillis(SystemClock.elapsedRealtime()) > maxAgeMs) null else location.toFix()
        } catch (e: Exception) {
            AppLog.w("FusedLocationSource", "lastLocation failed: ${e.javaClass.simpleName}")
            null
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    override fun apply(plan: LocationPlan) {
        if (!LocationPermissions.hasAnyLocation(context)) {
            stop()
            return
        }
        val looper = looper()
        if (plan.passive != appliedPassive) {
            client.removeLocationUpdates(passiveCallback)
            plan.passive?.let { client.requestLocationUpdates(it.toGmsRequest(), passiveCallback, looper) }
            appliedPassive = plan.passive
        }
        if (plan.active != appliedActive) {
            client.removeLocationUpdates(activeCallback)
            plan.active?.let { client.requestLocationUpdates(it.toGmsRequest(), activeCallback, looper) }
            appliedActive = plan.active
        }
        AppLog.d("FusedLocationSource", "plan=${plan.mode} passive=${plan.passive?.intervalMs} active=${plan.active?.priority}/${plan.active?.intervalMs}")
    }

    @Synchronized
    override fun stop() {
        client.removeLocationUpdates(passiveCallback)
        client.removeLocationUpdates(activeCallback)
        appliedPassive = null
        appliedActive = null
        lastEmittedElapsedMs = 0L
        thread?.quitSafely()
        thread = null
    }

    private fun looper(): Looper {
        val existing = thread
        if (existing != null && existing.isAlive) return existing.looper
        val created = HandlerThread("drive-alert-location").also { it.start() }
        thread = created
        return created.looper
    }
}
