package com.drivealert.app.alerts

import com.drivealert.app.domain.model.AlertSettings
import com.drivealert.app.domain.model.CameraAlert
import com.drivealert.app.domain.ports.AlertPresenter
import com.drivealert.app.notifications.CameraAlertNotifier
import com.drivealert.app.speech.AlertChimePlayer
import com.drivealert.app.speech.VoiceAlertManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delivers a camera warning through every channel the user enabled:
 * heads-up notification (always), then vibration, chime and voice.
 * Audio is preferred over visuals so the driver never needs to look at or
 * touch the phone.
 */
@Singleton
class AlertCoordinator @Inject constructor(
    private val notifier: CameraAlertNotifier,
    private val voice: VoiceAlertManager,
    private val chime: AlertChimePlayer,
    private val haptics: HapticAlerter,
    private val text: AlertTextFormatter,
) : AlertPresenter {

    override suspend fun prepare(settings: AlertSettings) {
        // Initialise TTS up front so the first warning is not delayed by engine start-up.
        if (settings.voiceAlerts && !settings.visualOnly) voice.initialize()
    }

    override suspend fun present(alert: CameraAlert, settings: AlertSettings) {
        notifier.showCameraAlert(alert)
        if (settings.visualOnly) return
        if (settings.vibration) runCatching { haptics.vibrate(alert.isFirstForCamera) }
        if (settings.alertSound) runCatching { chime.play() }
        if (settings.voiceAlerts) {
            voice.initialize() // no-op when already running; covers enabling voice mid-drive
            voice.speak(text.voicePhrase(alert))
        }
    }

    override fun showDriveEndCheck() = notifier.showDriveEndCheck()

    override fun hideDriveEndCheck() = notifier.cancelDriveEndCheck()

    override fun clearAlerts() = notifier.cancelCameraAlert()

    override fun release() = voice.shutdown()
}
