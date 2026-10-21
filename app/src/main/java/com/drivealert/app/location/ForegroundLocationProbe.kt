package com.drivealert.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.permissions.LocationPermissions
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Location reads for visible screens only (home screen card, calibration).
 * Nothing here runs in the background; collection stops with the screen.
 */
@Singleton
class ForegroundLocationProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Recent cached location if one exists (no GPS cost), otherwise a single
     * balanced-power fix. Called once when the home screen opens.
     */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(maxCachedAgeMs: Long = 120_000L): LocationFix? {
        if (!LocationPermissions.hasAnyLocation(context)) return null
        return try {
            val cached = client.lastLocation.awaitResult()
            if (cached != null && cached.ageMillis(SystemClock.elapsedRealtime()) <= maxCachedAgeMs) {
                return cached.toFix()
            }
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(maxCachedAgeMs)
                .setDurationMillis(15_000L)
                .build()
            client.getCurrentLocation(request, CancellationTokenSource().token).awaitResult()?.toFix()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * A fresh high-accuracy fix for a camera report (never a stale or coarse cached
     * position). Returns null if no fix arrives within 20 s.
     */
    @SuppressLint("MissingPermission")
    suspend fun freshPreciseLocation(): LocationFix? {
        if (!LocationPermissions.hasPreciseLocation(context)) return null
        return try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(5_000L)
                .setDurationMillis(20_000L)
                .build()
            client.getCurrentLocation(request, CancellationTokenSource().token).awaitResult()?.toFix()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * High-accuracy 1 s updates while the calibration screen is visible. The
     * request is removed as soon as the collector stops.
     */
    @SuppressLint("MissingPermission")
    fun calibrationUpdates(): Flow<LocationFix> = callbackFlow {
        if (!LocationPermissions.hasAnyLocation(context)) {
            close()
            return@callbackFlow
        }
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toFix()) }
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
