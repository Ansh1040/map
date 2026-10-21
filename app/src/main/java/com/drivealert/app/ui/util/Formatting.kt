package com.drivealert.app.ui.util

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.drivealert.app.R
import com.drivealert.app.domain.model.Directionality
import java.util.Locale

@Composable
fun formatDistance(distanceM: Int): String =
    if (distanceM >= 1_000) {
        stringResource(R.string.distance_km, String.format(Locale.getDefault(), "%.1f", distanceM / 1000.0))
    } else {
        stringResource(R.string.distance_m, distanceM)
    }

@Composable
fun formatDateTime(millis: Long): String {
    val context = LocalContext.current
    return DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
}

@Composable
fun formatRelativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** "Northbound (12°)" / "Both directions" / "Not known". */
@Composable
fun formatDirection(bearingDeg: Double?, directionality: Directionality): String {
    if (bearingDeg == null || directionality == Directionality.UNKNOWN) return stringResource(R.string.direction_unknown)
    val compass = stringResource(
        when (((bearingDeg + 22.5) % 360 / 45).toInt()) {
            0 -> R.string.dir_north
            1 -> R.string.dir_north_east
            2 -> R.string.dir_east
            3 -> R.string.dir_south_east
            4 -> R.string.dir_south
            5 -> R.string.dir_south_west
            6 -> R.string.dir_west
            else -> R.string.dir_north_west
        },
    )
    val degrees = bearingDeg.toInt()
    return if (directionality == Directionality.BOTH_DIRECTIONS) {
        stringResource(R.string.direction_both, compass, degrees)
    } else {
        stringResource(R.string.direction_one, compass, degrees)
    }
}
