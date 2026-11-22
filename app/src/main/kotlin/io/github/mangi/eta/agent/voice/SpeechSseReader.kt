package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import java.io.EOFException
import okhttp3.Response
import okio.BufferedSource

/** Only handles bounded frame splitting; event types, end conditions, and audio encoding are interpreted by each vendor's protocol. */
internal fun readSpeechSse(source: BufferedSource, onEvent: (String, String) -> Boolean) {
    var reading = true
    val decoder = SpeechSseDecoder { event, data -> reading = onEvent(event, data) }
    while (reading && !source.exhausted()) {
        val line = try {
            source.readUtf8LineStrict(SpeechHttp.MAX_JSON_BYTES)
        } catch (_: EOFException) {
            if (source.buffer.size > SpeechHttp.MAX_JSON_BYTES) {
                throw SpeechFailure(
                    SpeechErrorCode.PROTOCOL,
                    AppStrings.get(R.string.voice_i18n_audio_chunk_too_large, "voice audio chunk too large"),
                )
            }
            source.readUtf8()
        }
        decoder.line(line)
    }
    if (reading) decoder.end()
}

internal class SpeechSseDecoder(private val onEvent: (String, String) -> Unit) {
    private var event = ""
    private val data = StringBuilder()

    fun line(line: String) {
        when {
            line.isEmpty() -> end()
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("data:") -> {
                val value = line.substring(5).removePrefix(" ")
                if (data.length.toLong() + value.length + 1 > SpeechHttp.MAX_JSON_BYTES) {
                    throw SpeechFailure(
                        SpeechErrorCode.PROTOCOL,
                        AppStrings.get(R.string.voice_i18n_audio_chunk_too_large, "voice audio chunk too large"),
                    )
                }
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
    }

    fun end() {
        val payload = data.toString()
        val name = event
        data.clear()
        event = ""
        if (payload.isNotBlank()) onEvent(name, payload)
    }
}

/** Some services return business errors as HTTP 200 + JSON, so the protocol layer must read the error code. */
internal fun readSpeechEvents(response: Response, onEvent: (String, String) -> Boolean) {
    val source = response.body.source()
    if (response.body.contentType()?.subtype == "json") {
        if (source.request(SpeechHttp.MAX_JSON_BYTES + 1)) {
            throw SpeechFailure(
                SpeechErrorCode.PROTOCOL,
                AppStrings.get(R.string.voice_i18n_http_response_too_large, "voice service response too large"),
            )
        }
        onEvent("", source.readUtf8())
    } else {
        readSpeechSse(source, onEvent)
    }
}

internal class SpeechSegmentAudio(
    onAudio: (ByteArray) -> Unit,
    private val limit: Long = 24_000L * 2 * 180,
) {
    private val pcm = SpeechPcmDecoder(onAudio)
    private var bytes = 0L

    fun write(audio: ByteArray) {
        bytes += audio.size
        if (bytes > limit) {
            throw SpeechFailure(
                SpeechErrorCode.PROTOCOL,
                AppStrings.get(R.string.voice_i18n_audio_format_or_length_invalid, "voice audio format or length invalid"),
            )
        }
        pcm.write(audio)
    }

    fun end(completed: Boolean) {
        if (!completed || bytes == 0L) {
            throw SpeechFailure(
                SpeechErrorCode.PROTOCOL,
                AppStrings.get(R.string.voice_i18n_audio_incomplete, "voice audio incomplete, please retry"),
            )
        }
        pcm.end()
    }
}
