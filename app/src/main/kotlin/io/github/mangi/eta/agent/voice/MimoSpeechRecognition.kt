package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.MimoAsrConfig
import java.util.Base64
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal suspend fun recognizeMimoSpeech(config: MimoAsrConfig, key: String, wav: ByteArray): String =
    mimoAsrTranscript(SpeechHttp.json(mimoAsrRequest(config, key, wav)))

internal fun mimoAsrRequest(config: MimoAsrConfig, key: String, wav: ByteArray): Request {
    val encodedBytes = 4 * ((wav.size.toLong() + 2) / 3)
    if (encodedBytes > MAX_MIMO_ASR_BASE64_BYTES) {
        throw SpeechFailure(
            SpeechErrorCode.AUDIO,
            AppStrings.get(R.string.voice_i18n_mimo_asr_size_limit, "Recording exceeds Mimo recognition API size limit"),
        )
    }
    val audio = JSONObject().put("type", "input_audio").put(
        "input_audio", JSONObject().put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav)),
    )
    val body = JSONObject()
        .put("model", "mimo-v2.5-asr")
        .put("stream", false)
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(audio))))
        .put("asr_options", JSONObject().put("language", config.language.apiId))
    return SpeechHttp.jsonRequest(speechBaseUrl(config.baseUrl) + "/chat/completions", key, body).build()
}

internal fun mimoAsrTranscript(response: JSONObject): String {
    if (!response.isNull("error")) {
        throw SpeechFailure(
            SpeechErrorCode.SERVER,
            AppStrings.get(R.string.voice_i18n_mimo_asr_failed, "MiMo speech recognition failed，please check service permissions and configuration"),
        )
    }
    val choice = response.optJSONArray("choices")?.optJSONObject(0)
        ?: throw SpeechFailure(
            SpeechErrorCode.PROTOCOL,
            AppStrings.get(R.string.voice_i18n_mimo_asr_no_result, "MiMo did not return a recognition result"),
        )
    when (choice.optString("finish_reason")) {
        "stop" -> Unit
        "length" -> throw SpeechFailure(
            SpeechErrorCode.SERVER,
            AppStrings.get(R.string.voice_i18n_mimo_asr_incomplete, "MiMo recognition result incomplete，please shorten the recording and retry"),
        )
        "content_filter" -> throw SpeechFailure(
            SpeechErrorCode.SERVER,
            AppStrings.get(R.string.voice_i18n_mimo_asr_content_filter, "MiMo could not process this recording，please retry"),
        )
        else -> throw SpeechFailure(
            SpeechErrorCode.PROTOCOL,
            AppStrings.get(R.string.voice_i18n_mimo_asr_abnormal_finish, "MiMo did not complete speech recognition normally"),
        )
    }
    val text = choice.optJSONObject("message")?.opt("content") as? String
        ?: throw SpeechFailure(
            SpeechErrorCode.PROTOCOL,
            AppStrings.get(R.string.voice_i18n_mimo_asr_invalid_result, "MiMo returned an invalid recognition result"),
        )
    return text.trim()
}

private const val MAX_MIMO_ASR_BASE64_BYTES = 10_000_000L
