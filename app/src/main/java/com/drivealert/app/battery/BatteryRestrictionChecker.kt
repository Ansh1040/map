package com.drivealert.app.battery

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import com.drivealert.app.notifications.NotificationChannels
import com.drivealert.app.permissions.LocationPermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class DeviceFamily {
    /** OnePlus (OxygenOS), Oppo and Realme (ColorOS / Realme UI) share battery management. */
    ONEPLUS_OPPO_REALME,
    XIAOMI,
    VIVO,
    SAMSUNG,
    GOOGLE,
    MOTOROLA,
    NOTHING,
    OTHER;

    companion object {
        fun detect(manufacturer: String = Build.MANUFACTURER, brand: String = Build.BRAND): DeviceFamily {
            val m = "${manufacturer.lowercase()} ${brand.lowercase()}"
            return when {
                "oneplus" in m || "oppo" in m || "realme" in m -> ONEPLUS_OPPO_REALME
                "xiaomi" in m || "redmi" in m || "poco" in m -> XIAOMI
                "vivo" in m || "iqoo" in m -> VIVO
                "samsung" in m -> SAMSUNG
                "google" in m -> GOOGLE
                "motorola" in m || "lenovo" in m -> MOTOROLA
                "nothing" in m -> NOTHING
                else -> OTHER
            }
        }
    }
}

data class DeviceReadiness(
    val preciseLocationGranted: Boolean,
    val approximateLocationOnly: Boolean,
    val notificationsAllowed: Boolean,
    val alertChannelEnabled: Boolean,
    val locationServicesEnabled: Boolean,
    /** User restricted the app's background activity (Android 9+). */
    val backgroundRestricted: Boolean,
    /** Informational: unrestricted battery use is NOT required for alerts. */
    val ignoringBatteryOptimizations: Boolean,
    val powerSaveMode: Boolean,
    /** App standby bucket name (Android 9+), informational only. */
    val standbyBucket: String?,
    val deviceFamily: DeviceFamily,
) {
    /** Hard requirements for starting alerts. */
    val canStartAlerts: Boolean get() = preciseLocationGranted && locationServicesEnabled

    /** Items that could delay or hide warnings. */
    val warningCount: Int
        get() = listOf(
            !notificationsAllowed || !alertChannelEnabled,
            backgroundRestricted,
            powerSaveMode,
            approximateLocationOnly,
        ).count { it }
}

/**
 * Reads (never changes) the settings that affect alert reliability. Android
 * does not allow apps to silently change battery management, so the setup
 * assistant explains each item and opens the closest settings screen.
 */
@Singleton
class BatteryRestrictionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun check(): DeviceReadiness {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val notifications = NotificationManagerCompat.from(context)

        val channelEnabled = notifications.getNotificationChannel(NotificationChannels.CAMERA_ALERTS)
            ?.let { it.importance != NotificationManager.IMPORTANCE_NONE } ?: true

        return DeviceReadiness(
            preciseLocationGranted = LocationPermissions.hasPreciseLocation(context),
            approximateLocationOnly = LocationPermissions.hasApproximateOnly(context),
            notificationsAllowed = LocationPermissions.hasNotificationPermission(context) && notifications.areNotificationsEnabled(),
            alertChannelEnabled = channelEnabled,
            locationServicesEnabled = LocationPermissions.isLocationEnabled(context),
            backgroundRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activityManager.isBackgroundRestricted,
            ignoringBatteryOptimizations = power.isIgnoringBatteryOptimizations(context.packageName),
            powerSaveMode = power.isPowerSaveMode,
            standbyBucket = standbyBucketName(),
            deviceFamily = DeviceFamily.detect(),
        )
    }

    private fun standbyBucketName(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        return when (usage.appStandbyBucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "Active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "Working set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "Frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "Rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "Restricted"
            else -> "Other"
        }
    }
}
