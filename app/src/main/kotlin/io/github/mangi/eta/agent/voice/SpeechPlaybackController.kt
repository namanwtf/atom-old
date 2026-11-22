package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import java.io.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal data class SpeechPlaybackState(val messageId: String? = null, val loading: Boolean = false, val error: String? = null)

internal class SpeechPlaybackController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val beforePlayback: () -> Unit = {},
    private val interruptOnScreenOff: Boolean = true,
    private val afterPlayback: () -> Unit = {},
    private val synthesize: suspend (SpeechSettings, SpeechCredentials, String, (ByteArray) -> Unit) -> Unit = ::synthesizeSpeech,
    /** Whole-passage single-session streaming synthesis; when null only per-segment requests are used. */
    private val streamSynthesize: (suspend (SpeechSettings, SpeechCredentials, List<String>, (ByteArray) -> Unit) -> Unit)? = ::streamSpeech,
    private val createOutput: () -> SpeechAudioOutput = ::SpeechPcmOutput,
) {
    private val mutableState = MutableStateFlow(SpeechPlaybackState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var output: SpeechAudioOutput? = null
    private var lease: SpeechAudioLease? = null
    private var playbackActive = false

    fun speak(messageId: String, text: String, settings: SpeechSettings? = null, credentials: SpeechCredentials? = null) {
        stop()
        val session = generation
        mutableState.value = SpeechPlaybackState(messageId, loading = true)
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                lease = SpeechAudioLease(context, interruptOnScreenOff) { stop() }.also { it.acquire(playback = false) }
                val rawConfig = settings ?: SpeechSettingsRepository.settings()
                val rawSecrets = credentials ?: SpeechSettingsRepository.credentials(context)
                val (config, secrets) = SpeechSettingsRepository.resolveDeepSeekFallback(rawConfig, rawSecrets)
                validateSpeechSettings(config, secrets, synthesis = true)
                val readable = withContext(Dispatchers.Default) { SpeechText.readable(text) }
                if (readable.isBlank()) {
                    throw SpeechFailure(
                        SpeechErrorCode.NO_SPEECH,
                        AppStrings.get(R.string.voice_i18n_playback_no_readable_text, "This message has no readable text"),
                    )
                }
                if (readable.length > 30_000) {
                    throw SpeechFailure(
                        SpeechErrorCode.CONFIGURATION,
                        AppStrings.get(R.string.voice_i18n_playback_text_too_long, "Text too long，please choose a shorter answer to read aloud"),
                    )
                }
                lease?.requestPlaybackFocus { beforePlayback(); playbackActive = true }
                val player = createOutput()
                output = player
                playChunks(config, secrets, SpeechText.chunks(readable), player) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (session == generation) mutableState.value = SpeechPlaybackState(messageId)
                    }
                }
                player.finish()
                withTimeout(180_000) { while (!player.drained()) delay(20) }
                if (session == generation) stop()
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (session == generation) {
                    val failure = error.speechFailure()
                    AndroidAgentLogger.warn("Eta speech playback failed: code=${failure.code} type=${error.javaClass.simpleName}")
                    stop()
                    mutableState.value = SpeechPlaybackState(error = failure.userMessage)
                }
            } finally {
                if (session == generation) stop()
            }
        }
    }

    /**
     * Synthesis downloads at network speed and playback writes to the track at real-time speed, decoupled via a queue. If audio were written to the track directly in the synthesis callback,
     * the read stream would be dragged by the playback pace, and the server would wait a long time for the client to read.
     *
     * Prefer putting the whole passage into one streaming synthesis session; the audio is one continuous stream, fading in and out only at the very start and end.
     * When the streaming session fails before audio starts (e.g., the account has no realtime model, or the custom endpoint doesn't support WebSocket),
     * fall back to per-segment requests: lead playback by at most [PREFETCH_CHUNKS] segments, with [SpeechSegmentFade] doing fade-in/out at the seams.
     * If it fails again after audio has already started, report the error directly rather than re-reading from the beginning.
     */
    private suspend fun playChunks(
        config: SpeechSettings,
        secrets: SpeechCredentials,
        chunks: List<String>,
        player: SpeechAudioOutput,
        onFirstAudio: () -> Unit,
    ) = coroutineScope {
        val queue = Channel<SpeechQueueItem>(Channel.UNLIMITED)
        val ahead = Semaphore(PREFETCH_CHUNKS)
        launch {
            try {
                var streamed = false
                val stream = streamSynthesize
                if (stream != null && supportsSpeechSession(config.tts)) {
                    try {
                        ahead.acquire()
                        stream(config, secrets, chunks) { bytes ->
                            streamed = true
                            queue.trySend(SpeechQueueItem.Audio(bytes))
                        }
                        queue.send(SpeechQueueItem.ChunkEnd)
                        streamed = true
                    } catch (error: Exception) {
                        if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                        if (streamed) throw error
                        AndroidAgentLogger.warn("Eta speech stream unavailable, using segments: code=${error.speechFailure().code}")
                        ahead.release()
                    }
                }
                if (!streamed) for (chunk in chunks) {
                    ahead.acquire()
                    synthesize(config, secrets, chunk) { bytes -> queue.trySend(SpeechQueueItem.Audio(bytes)) }
                    queue.send(SpeechQueueItem.ChunkEnd)
                }
                queue.close()
            } catch (error: Throwable) {
                // A synthesis timeout is a CancellationException; the child coroutine ending with it does not cancel the parent scope, so it must be handed to the playback side via the queue.
                queue.close(error)
            }
        }
        withContext(Dispatchers.IO) {
            var announced = false
            val fade = SpeechSegmentFade()
            // When the track is full, write sleeps and retries; only by being interruptible can a host cancellation end this immediately, after which the track is closed.
            suspend fun play(bytes: ByteArray) {
                if (bytes.isNotEmpty()) runInterruptible { player.write(bytes) }
            }
            for (item in queue) when (item) {
                is SpeechQueueItem.Audio -> {
                    if (!announced) { announced = true; onFirstAudio() }
                    play(fade.write(item.bytes))
                }
                SpeechQueueItem.ChunkEnd -> {
                    play(fade.end())
                    ahead.release()
                }
            }
        }
    }

    fun stop() {
        generation++
        output?.close(); output = null
        job?.cancel(); job = null
        lease?.close(); lease = null
        if (playbackActive) afterPlayback()
        playbackActive = false
        mutableState.value = SpeechPlaybackState()
    }
}

private sealed interface SpeechQueueItem {
    class Audio(val bytes: ByteArray) : SpeechQueueItem
    data object ChunkEnd : SpeechQueueItem
}

private const val PREFETCH_CHUNKS = 2

internal interface SpeechAudioOutput : Closeable {
    fun write(bytes: ByteArray)
    fun finish()
    fun drained(): Boolean
}
