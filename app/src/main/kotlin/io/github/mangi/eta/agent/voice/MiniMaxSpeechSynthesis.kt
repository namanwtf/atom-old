package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.MiniMaxTtsConfig
import okhttp3.Request
import org.json.JSONObject

internal suspend fun synthesizeMiniMax(
    config: MiniMaxTtsConfig,
    key: String,
    text: String,
    onAudio: (ByteArray) -> Unit,
) {
    SpeechHttp.read(minimaxSpeechRequest(config, key, text)) { response ->
        val decoder = MiniMaxSpeechDecoder(onAudio)
        readSpeechEvents(response) { _, data -> decoder.event(data) }
        decoder.end()
    }
}

internal fun minimaxSpeechRequest(config: MiniMaxTtsConfig, key: String, text: String): Request =
    SpeechHttp.jsonRequest(
        speechBaseUrl(config.baseUrl) + "/v1/t2a_v2",
        key,
        JSONObject().put("model", config.model.apiId).put("text", text).put("stream", true)
            .put("stream_options", JSONObject().put("exclude_aggregated_audio", true))
            .put("voice_setting", JSONObject().put("voice_id", config.voice.trim())
                .put("speed", config.speed.toDouble()).put("vol", 1).put("pitch", 0))
            .put("audio_setting", JSONObject().put("format", "pcm").put("sample_rate", 24000).put("channel", 1))
            .put("text_normalization_mode", "basic"),
    ).header("Accept", "text/event-stream").build()

internal class MiniMaxSpeechDecoder(onAudio: (ByteArray) -> Unit) {
    private val audio = SpeechSegmentAudio(onAudio)
    private var completed = false

    fun event(value: String): Boolean {
        if (completed) return false
        val message = JSONObject(value)
        val code = message.getJSONObject("base_resp").getInt("status_code")
        if (code != 0) throw minimaxFailure(code)
        val data = message.getJSONObject("data")
        when (data.getInt("status")) {
            1 -> {
                val encoded = data.optString("audio")
                if (encoded.isNotEmpty()) audio.write(decodeHexAudio(encoded))
            }
            // The last packet's audio is the whole aggregated audio; even if the gateway ignores exclude_aggregated_audio, it must not be played twice.
            2 -> completed = true
            else -> throw SpeechFailure(
                SpeechErrorCode.PROTOCOL,
                AppStrings.get(R.string.voice_i18n_minimax_tts_invalid_status, "MiniMax returned an invalid audio status"),
            )
        }
        return !completed
    }

    fun end() = audio.end(completed)
}

private fun decodeHexAudio(value: String): ByteArray {
    if (value.length % 2 != 0) {
        throw SpeechFailure(
            SpeechErrorCode.PROTOCOL,
            AppStrings.get(R.string.voice_i18n_minimax_tts_encoding_incomplete, "MiniMax audio encoding incomplete"),
        )
    }
    return ByteArray(value.length / 2) { index ->
        val high = value[index * 2].digitToIntOrNull(16)
        val low = value[index * 2 + 1].digitToIntOrNull(16)
        if (high == null || low == null) {
            throw SpeechFailure(
                SpeechErrorCode.PROTOCOL,
                AppStrings.get(R.string.voice_i18n_minimax_tts_encoding_invalid, "MiniMax audio encoding invalid"),
            )
        }
        ((high shl 4) or low).toByte()
    }
}

private fun minimaxFailure(code: Int): SpeechFailure = when (code) {
    1001 -> SpeechFailure(
        SpeechErrorCode.TIMEOUT,
        AppStrings.get(R.string.voice_i18n_minimax_tts_timeout, "MiniMax voice synthesis timed out, please retry"),
    )
    1002, 1039 -> SpeechFailure(
        SpeechErrorCode.RATE_LIMITED,
        AppStrings.get(R.string.voice_i18n_minimax_tts_rate_limited, "MiniMax requests too frequent, please retry later"),
    )
    1004 -> SpeechFailure(
        SpeechErrorCode.AUTHENTICATION,
        AppStrings.get(R.string.voice_i18n_minimax_tts_auth_failed, "MiniMax authentication failed, please check API Key"),
    )
    1042, 2013 -> SpeechFailure(
        SpeechErrorCode.CONFIGURATION,
        AppStrings.get(R.string.voice_i18n_minimax_tts_invalid_config, "MiniMax cannot process the current text or voice, please check the configuration"),
    )
    else -> SpeechFailure(
        SpeechErrorCode.SERVER,
        AppStrings.get(R.string.voice_i18n_minimax_tts_failed_code, "MiniMax voice synthesis failed(%1\$d)", code),
    )
}
