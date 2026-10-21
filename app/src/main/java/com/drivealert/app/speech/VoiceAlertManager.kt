package com.drivealert.app.speech

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Text-to-speech voice warnings. The engine is created when an alert session
 * starts and shut down when it ends, so it holds no resources while alerts
 * are off.
 */
@Singleton
class VoiceAlertManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFocus: AudioFocusHelper,
) {
    private var tts: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()
    private val utteranceCounter = AtomicInteger()

    @Synchronized
    fun initialize() {
        if (tts != null) return
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        tts = TextToSpeech(context.applicationContext) { status ->
            val ok = status == TextToSpeech.SUCCESS && configureLanguage()
            deferred.complete(ok)
        }.apply {
            setAudioAttributes(audioFocus.attributes)
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = audioFocus.release()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = audioFocus.release()
                override fun onError(utteranceId: String?, errorCode: Int) = audioFocus.release()
            })
        }
    }

    /** Speaks [text], interrupting any older warning. Returns false if TTS is unavailable. */
    suspend fun speak(text: String): Boolean {
        val engine = tts ?: return false
        val isReady = withTimeoutOrNull(READY_TIMEOUT_MS) { ready.await() } ?: false
        if (!isReady) return false
        if (audioFocus.isInCall()) return false
        audioFocus.acquire()
        val params = Bundle()
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "alert-${utteranceCounter.incrementAndGet()}")
        if (result != TextToSpeech.SUCCESS) audioFocus.release()
        return result == TextToSpeech.SUCCESS
    }

    @Synchronized
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        audioFocus.release()
    }

    /** Prefers Indian English / Hindi voices to match the app language. */
    private fun configureLanguage(): Boolean {
        val engine = tts ?: return false
        val appLocale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val candidates = if (appLocale.language == "hi") {
            listOf(Locale.forLanguageTag("hi-IN"), Locale.forLanguageTag("en-IN"), Locale.ENGLISH)
        } else {
            listOf(Locale.forLanguageTag("en-IN"), Locale.UK, Locale.ENGLISH)
        }
        for (locale in candidates) {
            val result = engine.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) return true
        }
        return false
    }

    private companion object {
        const val READY_TIMEOUT_MS = 3_000L
    }
}
