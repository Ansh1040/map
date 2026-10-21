package com.drivealert.app.domain.model

data class AlertSettings(
    val voiceAlerts: Boolean = true,
    val alertSound: Boolean = true,
    val vibration: Boolean = true,
    /** Visual-only mode: no voice, sound or vibration regardless of the toggles above. */
    val visualOnly: Boolean = false,
    /** First warning distance in metres: 1000, 500 or 200. */
    val primaryAlertDistanceM: Int = AlertDistances.DEFAULT,
    /** Also warn again at the closer standard distances. */
    val closerReminders: Boolean = true,
    /** Include user-reported cameras that are still awaiting verification. */
    val includeUnverified: Boolean = false,
) {
    val thresholdsM: List<Int>
        get() = AlertDistances.thresholdsFor(primaryAlertDistanceM, closerReminders)

    val audioEnabled: Boolean get() = !visualOnly && (voiceAlerts || alertSound)
}

object AlertDistances {
    const val FAR = 1_000
    const val MEDIUM = 500
    const val NEAR = 200
    const val DEFAULT = MEDIUM
    val STANDARD = listOf(FAR, MEDIUM, NEAR)

    /** Descending list of warning thresholds for the selected primary distance. */
    fun thresholdsFor(primaryM: Int, closerReminders: Boolean): List<Int> {
        val primary = STANDARD.minByOrNull { kotlin.math.abs(it - primaryM) } ?: DEFAULT
        return if (closerReminders) STANDARD.filter { it <= primary } else listOf(primary)
    }
}
