package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal data class SpeechInputState(
    val phase: EtaSpeechPhase = EtaSpeechPhase.IDLE,
    val preview: String = "",
    val level: Float = 0f,
    val progress: String = "",
    val error: String? = null,
    val configureAvailable: Boolean = false,
    val holdToTalk: Boolean = false,
) {
    val active: Boolean get() = phase != EtaSpeechPhase.IDLE
}

internal class SpeechInputController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val automaticEndpoint: Boolean = false,
    private val beforeCapture: () -> Unit = {},
    private val afterCapture: () -> Unit = {},
    private val refiner: SpeechTranscriptRefiner = SpeechTranscriptRefiner(),
    private val createRecorder: () -> SpeechAudioCapture = ::SpeechRecorder,
    private val recognizeStream: suspend (
        SpeechSettings,
        SpeechCredentials,
        ReceiveChannel<ByteArray>,
        () -> Unit,
        (String) -> Unit,
        () -> Unit,
        Int,
    ) -> String = ::recognizeSpeechStream,
    private val recognizeFile: suspend (
        Context,
        SpeechSettings,
        SpeechCredentials,
        ByteArray,
        (String) -> Unit,
    ) -> String = ::recognizeSpeechFile,
    private val loadSettings: suspend () -> SpeechSettings = SpeechSettingsRepository::settings,
    private val loadCredentials: suspend () -> SpeechCredentials = { SpeechSettingsRepository.credentials(context) },
    private val onHoldResult: ((String) -> Unit)? = null,
    private val onResult: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(SpeechInputState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var finishRequested = false
    private var job: Job? = null
    private var recorder: SpeechAudioCapture? = null
    private var lease: SpeechAudioLease? = null
    private var capturing = false

    /**
     * [holdToTalk] means the user holds to talk: it ends only on release, and pauses in between are never auto-sent.
     * Auto-send on pause only applies to entries that declared [automaticEndpoint] at construction; chat dictation always waits for user confirmation.
     */
    fun start(settings: SpeechSettings? = null, credentials: SpeechCredentials? = null, holdToTalk: Boolean = false) {
        cancel()
        val session = generation
        mutableState.value = SpeechInputState(
            phase = EtaSpeechPhase.STARTING,
            progress = AppStrings.get(R.string.voice_i18n_progress_connecting, "Connecting"),
            holdToTalk = holdToTalk,
        )
        job = scope.launch(Dispatchers.Main.immediate) {
            try {
                val rawConfig = settings ?: loadSettings()
                if (!rawConfig.recognitionEnabled) return@launch
                lease = SpeechAudioLease(context) { cancel() }.also { it.acquire(playback = false) }
                val rawSecrets = credentials ?: loadCredentials()
                val (config, secrets) = SpeechSettingsRepository.resolveDeepSeekFallback(rawConfig, rawSecrets)
                validateSpeechSettings(config, secrets, synthesis = false)
                val endpointSilenceMs = if (automaticEndpoint && !holdToTalk) config.autoSendSilenceMs else 0
                val audio = createRecorder()
                recorder = audio
                fun update(transform: (SpeechInputState) -> SpeechInputState) {
                    scope.launch(Dispatchers.Main.immediate) {
                        if (generation == session) mutableState.value = transform(mutableState.value)
                    }
                }
                val text = coroutineScope {
                    var recording: Job? = null
                    val begin = {
                        if (recording == null) {
                            beforeCapture()
                            capturing = true
                            recording = launch {
                                try {
                                    audio.record(
                                        onReady = {
                                            update {
                                                it.copy(
                                                    phase = EtaSpeechPhase.LISTENING,
                                                    progress = if (it.holdToTalk) {
                                                        AppStrings.get(
                                                            R.string.voice_i18n_progress_hold_to_talk,
                                                            "Release to send，move away to cancel",
                                                        )
                                                    } else {
                                                        AppStrings.get(
                                                            R.string.voice_i18n_progress_listening_tap_done,
                                                            "Listening，tap done to finish",
                                                        )
                                                    },
                                                )
                                            }
                                        },
                                        onLevel = { level -> update { it.copy(level = level) } },
                                    )
                                } finally {
                                    if (generation == session) releaseCapture()
                                }
                                update {
                                    it.copy(
                                        phase = EtaSpeechPhase.RECOGNIZING,
                                        level = 0f,
                                        progress = if (it.holdToTalk) {
                                            AppStrings.get(
                                                R.string.voice_i18n_progress_recognizing_and_sending,
                                                "Recognizing and sending",
                                            )
                                        } else {
                                            AppStrings.get(R.string.voice_i18n_progress_recognizing, "Recognizing")
                                        },
                                    )
                                }
                            }
                        }
                        Unit
                    }
                    try {
                        if (config.asr == AsrProvider.QWEN_REALTIME || config.asr == AsrProvider.DOUBAO) {
                            recognizeStream(
                                config,
                                secrets,
                                audio.frames,
                                begin,
                                { preview -> update { it.copy(preview = preview) } },
                                { audio.finish() },
                                endpointSilenceMs,
                            )
                        } else {
                            begin()
                            val wav = withTimeout(65_000) { collectSpeechAudio(audio.frames) }
                            // Closing the channel may synchronously wake the collector; the old session must not start a request before cancellation propagation completes.
                            coroutineContext.ensureActive()
                            if (generation != session) throw CancellationException("Speech session replaced")
                            recognizeFile(context, config, secrets, wav) { progress ->
                                update { it.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = progress) }
                            }
                        }
                    } finally {
                        audio.cancel()
                        recording?.cancel()
                    }
                }
                if (text.isBlank()) {
                    throw SpeechFailure(
                        SpeechErrorCode.NO_SPEECH,
                        AppStrings.get(R.string.voice_i18n_no_speech_recognized, "No speech recognized，please retry"),
                    )
                }
                val final = refined(session, config.refineTranscript, text)
                if (generation == session) {
                    val holdMode = mutableState.value.holdToTalk
                    cancel()
                    if (holdMode && onHoldResult != null) {
                        onHoldResult(final)
                    } else {
                        onResult(final)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (generation == session) {
                    val failure = error.speechFailure()
                    AndroidAgentLogger.warn("Eta speech recognition failed: code=${failure.code} type=${error.javaClass.simpleName}")
                    cancel()
                    mutableState.value = SpeechInputState(
                        error = failure.userMessage,
                        configureAvailable = failure.code == SpeechErrorCode.CONFIGURATION ||
                            failure.code == SpeechErrorCode.CREDENTIALS ||
                            failure.code == SpeechErrorCode.AUTHENTICATION,
                    )
                }
            } finally {
                if (generation == session) cancel()
            }
        }
    }

    /** Correction failure does not affect delivery; progress is shown only while the correction still belongs to the current session. */
    private suspend fun refined(session: Long, refineTranscript: Boolean, text: String): String {
        if (!refineTranscript || generation != session) return text
        mutableState.value = mutableState.value.copy(
            phase = EtaSpeechPhase.RECOGNIZING,
            preview = text,
            progress = AppStrings.get(R.string.voice_i18n_progress_refining, "Proofreading"),
            level = 0f,
        )
        return try {
            refiner.refine(text)
        } catch (error: Exception) {
            if (error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            val failure = error.speechFailure()
            AndroidAgentLogger.warn("Eta speech refine skipped: code=${failure.code} type=${error.javaClass.simpleName}")
            text
        }
    }

    fun finish() {
        if (!mutableState.value.active || finishRequested) return
        finishRequested = true
        if (mutableState.value.phase == EtaSpeechPhase.STARTING) { cancel(); return }
        recorder?.finish()
        val progressText = if (mutableState.value.holdToTalk) {
            AppStrings.get(R.string.voice_i18n_progress_recognizing_and_sending, "Recognizing and sending")
        } else {
            AppStrings.get(R.string.voice_i18n_progress_recognizing, "Recognizing")
        }
        mutableState.value = mutableState.value.copy(phase = EtaSpeechPhase.RECOGNIZING, progress = progressText, level = 0f)
    }

    fun cancel() {
        generation++
        finishRequested = false
        // Cancel the recognition coroutine first, so closing the recording channel isn't treated as a normal recording completion and submitted as a request.
        job?.cancel(); job = null
        recorder?.cancel(); recorder = null
        releaseCapture()
        lease?.close(); lease = null
        mutableState.value = SpeechInputState()
    }

    private fun releaseCapture() {
        if (capturing) afterCapture()
        capturing = false
    }
}
