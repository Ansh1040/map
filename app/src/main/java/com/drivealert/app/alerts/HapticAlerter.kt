package com.drivealert.app.alerts

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HapticAlerter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun vibrate(firstWarning: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val timings = if (firstWarning) longArrayOf(0, 220, 120, 220) else longArrayOf(0, 160)
        v.vibrate(VibrationEffect.createWaveform(timings, -1))
    }
}
