package com.drivealert.app.battery

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Opens the closest system settings screen. Every intent falls back to the
 * app's details page, which exists on all Android builds including OxygenOS.
 */
object SystemSettingsIntents {

    fun openAppDetails(context: Context) {
        launch(context, appDetailsIntent(context))
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        launchOrFallback(context, intent)
    }

    fun openLocationSettings(context: Context) {
        launchOrFallback(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }

    fun openBatterySaverSettings(context: Context) {
        launchOrFallback(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
    }

    /** The list screen only; the app never requests unrestricted battery access. */
    fun openBatteryOptimizationList(context: Context) {
        launchOrFallback(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openAppLanguageSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
            launchOrFallback(context, intent)
        } else {
            launchOrFallback(context, Intent(Settings.ACTION_LOCALE_SETTINGS))
        }
    }

    fun openUrl(context: Context, url: String) {
        launchOrFallback(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    private fun launchOrFallback(context: Context, intent: Intent) {
        if (!launch(context, intent)) launch(context, appDetailsIntent(context))
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
