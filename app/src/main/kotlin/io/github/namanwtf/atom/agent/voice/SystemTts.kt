package io.github.namanwtf.atom.agent.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * The device's own voice.
 *
 * Buddy reads replies with Android's built-in TextToSpeech; Atom synthesised
 * every sentence through a cloud provider, so a failing key (Qwen TTS
 * especially) surfaced as a crash instead of a voice. This is the fallback:
 * no network, no credentials, always available.
 */
internal object SystemTts {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: Pending? = null

    private class Pending(val text: String, val onDone: () -> Unit)

    fun isAvailable(context: Context): Boolean = ready || ensure(context)

    fun speak(context: Context, text: String, onDone: () -> Unit) {
        if (!ensure(context)) {
            onDone()
            return
        }
        val tts = engine ?: run { onDone(); return }
        if (!ready) {
            pending = Pending(text, onDone)
            return
        }
        speakNow(tts, text, onDone)
    }

    fun stop() {
        pending = null
        runCatching { engine?.stop() }
    }

    private fun ensure(context: Context): Boolean {
        if (engine != null) return true
        val created = runCatching {
            TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                val tts = engine
                if (ready && tts != null) {
                    runCatching {
                        tts.language = Locale.getDefault()
                        tts.setSpeechRate(1.0f)
                    }
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = Unit
                        override fun onError(utteranceId: String?) = Unit
                    })
                    pending?.let { p ->
                        pending = null
                        speakNow(tts, p.text, p.onDone)
                    }
                }
            }
        }.getOrNull()
        engine = created
        return created != null
    }

    private fun speakNow(tts: TextToSpeech, text: String, onDone: () -> Unit) {
        val id = UUID.randomUUID().toString()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) onDone()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) onDone()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id) onDone()
            }
        })
        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }
}
