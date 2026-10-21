package com.drivealert.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transient, ducking audio focus on the navigation-guidance usage, the same
 * category navigation apps use. Music is lowered briefly, not paused, and the
 * volume follows the normal Android media/navigation volume controls.
 */
@Singleton
class AudioFocusHelper @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { }
        .build()

    @Volatile
    private var held = false

    fun acquire(): Boolean {
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        held = held || granted
        return granted
    }

    fun release() {
        if (held) {
            audioManager.abandonAudioFocusRequest(request)
            held = false
        }
    }

    /** True during a phone call; warnings are then shown visually only. */
    fun isInCall(): Boolean = audioManager.mode == AudioManager.MODE_IN_CALL ||
        audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
}
