package io.github.namanwtf.atom.agent.voice

import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.core.AppStrings
import io.github.namanwtf.atom.data.model.SpeechCredentials
import io.github.namanwtf.atom.data.model.SpeechSettings
import io.github.namanwtf.atom.data.model.TtsProvider
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONObject

internal fun supportsSpeechSession(provider: TtsProvider): Boolean = when (provider) {
    TtsProvider.QWEN, TtsProvider.DOUBAO, TtsProvider.DEEPSEEK -> true
    TtsProvider.NONE, TtsProvider.GEMINI, TtsProvider.MINIMAX, TtsProvider.MIMO -> false
}

/**
 * The whole passage is read aloud in a single streaming synthesis session: text is fed into the same session sentence by sentence, and the server produces audio continuously.
 * Compared to per-segment independent requests, pitch, volume, and rate stay consistent throughout, with no seams or pause jitter between segments.
 * Audio is handed off via [onAudio] in arrival order; any stage failure ends with [SpeechFailure].
 */
internal suspend fun streamSpeech(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = withTimeout(STREAM_TIMEOUT_MS) {
    when (settings.tts) {
        TtsProvider.QWEN -> streamQwen(settings, credentials, chunks, onAudio)
        TtsProvider.DOUBAO -> streamDoubao(settings, credentials, chunks, onAudio)
        TtsProvider.DEEPSEEK -> streamDeepSeekSpeech(settings.deepseekTts, credentials.deepseekTts, chunks, onAudio)
        TtsProvider.NONE -> throw SpeechFailure(
            SpeechErrorCode.CONFIGURATION,
            AppStrings.get(R.string.voice_i18n_tts_provider_not_selected, "Please select a voice read-aloud service first"),
        )
        TtsProvider.GEMINI, TtsProvider.MINIMAX, TtsProvider.MIMO ->
            throw SpeechFailure(
                SpeechErrorCode.CONFIGURATION,
                AppStrings.get(R.string.voice_i18n_tts_uses_segmented_streaming, "The current voice service uses segmented streaming synthesis"),
            )
    }
}

private suspend fun streamQwen(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = coroutineScope {
    val request = Request.Builder().url(speechBaseUrl(settings.qwenTts.endpoint())
        .replaceFirst("https://", "wss://") + "/api-ws/v1/realtime?model=qwen3-tts-flash-realtime")
        .header("Authorization", "Bearer ${credentials.qwenTts.trim()}").build()
    SpeechSocket(request).use { socket ->
        socket.awaitReady()
        fun event(type: String) = JSONObject().put("type", type).put("event_id", UUID.randomUUID().toString())
        // server_commit is sentence-split semantically by the server; the client just appends text.
        socket.text(event("session.update").put("session", JSONObject()
            .put("mode", "server_commit").put("voice", settings.qwenVoice).put("language_type", "Auto")
            .put("response_format", "pcm").put("sample_rate", SAMPLE_RATE)).toString())
        var audioBytes = 0L
        var sent = false
        for (bytes in socket.messages) {
            val message = JSONObject(bytes.toString(Charsets.UTF_8))
            when (message.optString("type")) {
                "session.updated" -> if (!sent) {
                    sent = true
                    chunks.forEach { socket.text(event("input_text_buffer.append").put("text", it).toString()) }
                    socket.text(event("session.finish").toString())
                }
                "response.audio.delta" -> {
                    val audio = Base64.getDecoder().decode(message.optString("delta"))
                    audioBytes = checkedAudio(audioBytes, audio.size)
                    onAudio(audio)
                }
                "session.finished" -> {
                    if (audioBytes == 0L) {
                        throw SpeechFailure(
                            SpeechErrorCode.PROTOCOL,
                            AppStrings.get(R.string.voice_i18n_audio_incomplete, "voice audio incomplete, please retry"),
                        )
                    }
                    return@coroutineScope
                }
                "error" -> throw SpeechFailure(
                    SpeechErrorCode.SERVER,
                    AppStrings.get(R.string.voice_i18n_qwen_tts_stream_failed, "Qwen voice synthesis failed, please check model permissions and configuration"),
                )
            }
        }
        throw SpeechFailure(
            SpeechErrorCode.PROTOCOL,
            AppStrings.get(R.string.voice_i18n_qwen_tts_connection_ended_early, "Qwen voice synthesis connection ended early"),
        )
    }
}

private suspend fun streamDoubao(
    settings: SpeechSettings,
    credentials: SpeechCredentials,
    chunks: List<String>,
    onAudio: (ByteArray) -> Unit,
) = coroutineScope {
    val request = Request.Builder().url(speechBaseUrl(settings.doubaoTts.baseUrl)
        .replaceFirst("https://", "wss://") + "/api/v3/tts/bidirection")
        .doubaoHeaders(settings.doubaoTts, credentials.doubaoTts, "seed-tts-2.0").build()
    SpeechSocket(request).use { socket ->
        socket.awaitReady()
        val sessionId = UUID.randomUUID().toString()
        val user = JSONObject().put("uid", UUID.randomUUID().toString())
        fun params(text: String? = null) = JSONObject().put("speaker", settings.doubaoVoice)
            .put("audio_params", JSONObject().put("format", "pcm").put("sample_rate", SAMPLE_RATE))
            .apply { text?.let { put("text", it) } }
        fun payload(event: Int, text: String? = null) = JSONObject().put("user", user).put("event", event)
            .put("namespace", "BidirectionalTTS").put("req_params", params(text)).toString()
        val sessionStarted = CompletableDeferred<Unit>()
        // Reading must run in parallel with sending: audio arrives quickly, and a full message channel is treated as a backpressure failure and disconnected.
        val sender = launch {
            sessionStarted.await()
            chunks.forEach { socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.TASK_REQUEST, sessionId, payload(DoubaoTtsCodec.TASK_REQUEST, it))) }
            socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.FINISH_SESSION, sessionId, "{}"))
        }
        socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.START_CONNECTION, null, "{}"))
        var audioBytes = 0L
        try {
            for (bytes in socket.messages) {
                val frame = DoubaoTtsCodec.decode(bytes)
                if (frame.errorCode != null) {
                    throw SpeechFailure(
                        SpeechErrorCode.SERVER,
                        AppStrings.get(
                            R.string.voice_i18n_doubao_tts_failed_code,
                            "Doubao voice synthesis failed(%1\$d)",
                            frame.errorCode,
                        ),
                    )
                }
                when (frame.event) {
                    DoubaoTtsCodec.CONNECTION_STARTED -> socket.binary(DoubaoTtsCodec.encode(
                        DoubaoTtsCodec.START_SESSION, sessionId, payload(DoubaoTtsCodec.START_SESSION),
                    ))
                    DoubaoTtsCodec.SESSION_STARTED -> sessionStarted.complete(Unit)
                    DoubaoTtsCodec.TTS_RESPONSE -> if (frame.audio && frame.payload.isNotEmpty()) {
                        audioBytes = checkedAudio(audioBytes, frame.payload.size)
                        onAudio(frame.payload)
                    }
                    DoubaoTtsCodec.SESSION_FINISHED -> {
                        runCatching { socket.binary(DoubaoTtsCodec.encode(DoubaoTtsCodec.FINISH_CONNECTION, null, "{}")) }
                        if (audioBytes == 0L) {
                            throw SpeechFailure(
                                SpeechErrorCode.PROTOCOL,
                                AppStrings.get(R.string.voice_i18n_audio_incomplete, "voice audio incomplete, please retry"),
                            )
                        }
                        return@coroutineScope
                    }
                    DoubaoTtsCodec.CONNECTION_FAILED, DoubaoTtsCodec.SESSION_FAILED ->
                        throw SpeechFailure(
                            SpeechErrorCode.SERVER,
                            AppStrings.get(R.string.voice_i18n_doubao_tts_stream_failed, "Doubao voice synthesis failed, please check the voice and service configuration"),
                        )
                }
            }
            throw SpeechFailure(
                SpeechErrorCode.PROTOCOL,
                AppStrings.get(R.string.voice_i18n_doubao_tts_connection_ended_early, "Doubao voice synthesis connection ended early"),
            )
        } finally {
            sender.cancel()
        }
    }
}

private fun checkedAudio(total: Long, added: Int): Long {
    val next = total + added
    if (next > MAX_AUDIO_BYTES) {
        throw SpeechFailure(
            SpeechErrorCode.PROTOCOL,
            AppStrings.get(R.string.voice_i18n_audio_format_or_length_invalid, "voice audio format or length invalid"),
        )
    }
    return next
}

private const val SAMPLE_RATE = 24_000
/** The upper bound is estimated the same as per-segment requests at 24 kHz/16-bit; only one connection is opened for the whole passage, and duration is relaxed by the body limit. */
private const val MAX_AUDIO_BYTES = SAMPLE_RATE * 2L * 60 * 20
private const val STREAM_TIMEOUT_MS = 20 * 60_000L
