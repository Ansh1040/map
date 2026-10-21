package com.drivealert.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.location.LocationManager

/**
 * Drive Alert needs only foreground ("while using the app") location:
 * the user starts a location foreground service from the visible app.
 * ACCESS_BACKGROUND_LOCATION is intentionally never requested.
 */
object LocationPermissions {
    val FOREGROUND_LOCATION = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun hasPreciseLocation(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasAnyLocation(context: Context): Boolean =
        hasPreciseLocation(context) || granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Approximate-only grant: camera alerts cannot work reliably. */
    fun hasApproximateOnly(context: Context): Boolean = hasAnyLocation(context) && !hasPreciseLocation(context)

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun isLocationEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
