package com.drivealert.app.location

import android.location.Location
import android.os.Build
import com.drivealert.app.domain.model.LocationFix
import com.drivealert.app.domain.model.LocationPriority
import com.drivealert.app.domain.model.LocationRequestSpec
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

fun Location.toFix(): LocationFix = LocationFix(
    latitude = latitude,
    longitude = longitude,
    accuracyM = if (hasAccuracy()) accuracy else 999f,
    speedMps = if (hasSpeed()) speed else null,
    bearingDeg = if (hasBearing()) bearing else null,
    bearingAccuracyDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
    altitudeM = if (hasAltitude()) altitude else null,
    verticalAccuracyM = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    timeMillis = time,
    elapsedRealtimeMillis = elapsedRealtimeNanos / 1_000_000L,
    isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider,
)

fun Location.ageMillis(nowElapsedMillis: Long): Long = nowElapsedMillis - elapsedRealtimeNanos / 1_000_000L

fun LocationPriority.toGms(): Int = when (this) {
    LocationPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
    LocationPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
    LocationPriority.LOW_POWER -> Priority.PRIORITY_LOW_POWER
    LocationPriority.PASSIVE -> Priority.PRIORITY_PASSIVE
}

fun LocationRequestSpec.toGmsRequest(): LocationRequest =
    LocationRequest.Builder(priority.toGms(), intervalMs)
        .setMinUpdateIntervalMillis(minUpdateIntervalMs)
        .setMaxUpdateDelayMillis(maxUpdateDelayMs)
        .setWaitForAccurateLocation(false)
        .build()

/** Minimal Task -> coroutine bridge (avoids an extra dependency). */
suspend fun <T> Task<T>.awaitResult(): T? = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
