package com.drivealert.app.speech

import android.media.AudioFormat
import android.media.AudioTrack
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Short two-tone chime synthesised in memory (no audio assets) and played on
 * the navigation-guidance stream, so it follows the user's volume settings and
 * Bluetooth/car audio routing like navigation prompts do.
 */
@Singleton
class AlertChimePlayer @Inject constructor(
    private val audioFocus: AudioFocusHelper,
) {
    private val samples: ShortArray by lazy { synthesize() }

    suspend fun play() = withContext(Dispatchers.Default) {
        if (audioFocus.isInCall()) return@withContext
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(audioFocus.attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
        } catch (e: Exception) {
            return@withContext
        }
        try {
            audioFocus.acquire()
            track.write(samples, 0, samples.size)
            track.play()
            delay(durationMs + 50)
        } finally {
            track.release()
            audioFocus.release()
        }
    }

    private val durationMs: Long get() = TONES.sumOf { it.second }

    private fun synthesize(): ShortArray {
        val out = ArrayList<Short>()
        for ((frequency, millis) in TONES) {
            val count = (SAMPLE_RATE * millis / 1000).toInt()
            val fade = (SAMPLE_RATE * 0.012).toInt()
            for (i in 0 until count) {
                val envelope = min(1.0, min(i.toDouble() / fade, (count - i).toDouble() / fade))
                val value = sin(2 * PI * frequency * i / SAMPLE_RATE) * envelope * AMPLITUDE
                out += (value * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out.toShortArray()
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
        const val AMPLITUDE = 0.55
        val TONES = listOf(880.0 to 130L, 1_174.7 to 190L)
    }
}
