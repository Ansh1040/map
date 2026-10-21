package com.drivealert.app.alerts

import android.content.Context
import androidx.annotation.StringRes
import com.drivealert.app.R
import com.drivealert.app.domain.model.CameraAlert
import com.drivealert.app.domain.model.CameraType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of alert wording for notifications, voice and UI.
 * Wording focuses on compliance ("Drive safely", "Please slow down") and
 * never suggests that alerts help avoid penalties.
 */
@Singleton
class AlertTextFormatter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun title(type: CameraType): String = context.getString(titleRes(type))

    /** "500 m • Speed limit 60 km/h" */
    fun body(alert: CameraAlert): String {
        val distance = distanceLabel(alert.distanceM)
        val limit = alert.camera.speedLimitKmh
        return if (limit != null) context.getString(R.string.alert_body_with_limit, distance, limit) else distance
    }

    /** "Current speed 48 km/h — Drive safely" */
    fun detail(alert: CameraAlert): String {
        val speed = alert.speedKmh
        val advice = context.getString(if (alert.isOverSpeedLimit) R.string.advice_slow_down else R.string.advice_drive_safely)
        val base = if (speed != null) context.getString(R.string.alert_detail_speed, speed, advice) else advice
        return if (alert.camera.verificationStatus.isTrusted) base else context.getString(R.string.alert_detail_unverified, base)
    }

    fun voicePhrase(alert: CameraAlert): String {
        val distance = spokenDistance(alert.distanceM)
        val sentence = if (alert.isFirstForCamera) {
            val limit = alert.camera.speedLimitKmh
            if (limit != null) {
                context.getString(R.string.voice_first_with_limit, title(alert.camera.type), distance, limit)
            } else {
                context.getString(R.string.voice_first, title(alert.camera.type), distance)
            }
        } else {
            context.getString(R.string.voice_reminder, distance)
        }
        val advice = if (alert.isOverSpeedLimit) " " + context.getString(R.string.advice_slow_down) + "." else ""
        val unverified = if (alert.camera.verificationStatus.isTrusted) "" else " " + context.getString(R.string.voice_unverified)
        return sentence + advice + unverified
    }

    fun distanceLabel(distanceM: Int): String =
        if (distanceM >= 1_000) {
            context.getString(R.string.distance_km, String.format(Locale.getDefault(), "%.1f", distanceM / 1000.0))
        } else {
            context.getString(R.string.distance_m, distanceM)
        }

    private fun spokenDistance(distanceM: Int): String =
        if (distanceM >= 1_000) {
            context.getString(R.string.voice_distance_km, String.format(Locale.getDefault(), "%.1f", distanceM / 1000.0))
        } else {
            context.getString(R.string.voice_distance_m, distanceM)
        }

    companion object {
        @StringRes
        fun titleRes(type: CameraType): Int = when (type) {
            CameraType.FIXED_SPEED -> R.string.alert_title_fixed_speed
            CameraType.MOBILE_SPEED -> R.string.alert_title_mobile_speed
            CameraType.RED_LIGHT -> R.string.alert_title_red_light
            CameraType.SPEED_AND_RED_LIGHT -> R.string.alert_title_speed_red_light
            CameraType.ANPR -> R.string.alert_title_anpr
            CameraType.BUS_LANE -> R.string.alert_title_bus_lane
            CameraType.RESTRICTED_ZONE -> R.string.alert_title_restricted_zone
            CameraType.USER_REPORTED -> R.string.alert_title_user_reported
        }

        @StringRes
        fun typeNameRes(type: CameraType): Int = when (type) {
            CameraType.FIXED_SPEED -> R.string.camera_type_fixed_speed
            CameraType.MOBILE_SPEED -> R.string.camera_type_mobile_speed
            CameraType.RED_LIGHT -> R.string.camera_type_red_light
            CameraType.SPEED_AND_RED_LIGHT -> R.string.camera_type_speed_red_light
            CameraType.ANPR -> R.string.camera_type_anpr
            CameraType.BUS_LANE -> R.string.camera_type_bus_lane
            CameraType.RESTRICTED_ZONE -> R.string.camera_type_restricted_zone
            CameraType.USER_REPORTED -> R.string.camera_type_user_reported
        }
    }
}
