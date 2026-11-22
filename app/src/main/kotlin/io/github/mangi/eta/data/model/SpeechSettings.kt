package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

private const val MIMO_SPEECH_BASE_URL = "https://api.xiaomimimo.com/v1"
private const val DEEPSEEK_SPEECH_BASE_URL = "https://api.deepseek.com"

@Serializable
enum class AsrProvider { QWEN_REALTIME, QWEN_FLASH, QWEN_FILE, DOUBAO, MIMO, DEEPSEEK }

@Serializable
enum class TtsProvider { NONE, QWEN, DOUBAO, GEMINI, MINIMAX, MIMO, DEEPSEEK }

@Serializable
enum class SpeechRegion { BEIJING, SINGAPORE }

@Serializable
data class QwenSpeechConfig(
    val region: SpeechRegion = SpeechRegion.BEIJING,
    val baseUrl: String = "",
) {
    fun endpoint(): String = baseUrl.trim().trimEnd('/').ifEmpty {
        if (region == SpeechRegion.BEIJING) "https://dashscope.aliyuncs.com"
        else "https://dashscope-intl.aliyuncs.com"
    }
}

@Serializable
enum class MimoAsrLanguage(val apiId: String) {
    AUTO("auto"),
    CHINESE("zh"),
    ENGLISH("en"),
}

@Serializable
data class MimoAsrConfig(
    val baseUrl: String = MIMO_SPEECH_BASE_URL,
    val language: MimoAsrLanguage = MimoAsrLanguage.AUTO,
)

@Serializable
data class DoubaoSpeechConfig(
    val baseUrl: String = "https://openspeech.bytedance.com",
    val legacyAuth: Boolean = false,
    val appId: String = "",
    val resourceId: String = "",
)

@Serializable
enum class GeminiTtsModel(val apiId: String) {
    FLASH("gemini-3.8-flash-tts"),
    FLASH_LITE("gemini-3.8-flash-lite-tts"),
}

@Serializable
data class GeminiTtsConfig(
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val model: GeminiTtsModel = GeminiTtsModel.FLASH,
    val voice: String = "Kore",
    val style: String = "",
)

@Serializable
enum class MiniMaxTtsModel(val apiId: String) {
    TURBO("speech-2.8-turbo"),
    HD("speech-2.8-hd"),
}

@Serializable
data class MiniMaxTtsConfig(
    val baseUrl: String = "https://api.minimax.cn",
    val model: MiniMaxTtsModel = MiniMaxTtsModel.TURBO,
    val voice: String = "female-chengshu",
    val speed: Float = 1f,
)

@Serializable
data class MimoTtsConfig(
    val baseUrl: String = MIMO_SPEECH_BASE_URL,
    val voice: String = "mimo_default",
    val style: String = "",
)

@Serializable
data class DeepSeekAsrConfig(
    val baseUrl: String = DEEPSEEK_SPEECH_BASE_URL,
)

@Serializable
data class DeepSeekTtsConfig(
    val baseUrl: String = DEEPSEEK_SPEECH_BASE_URL,
    val voice: String = "stella",
    val filterMarkdown: Boolean = true,
)

@Serializable
data class SpeechOssConfig(
    val region: String = "cn-beijing",
    val endpoint: String = "https://oss-cn-beijing.aliyuncs.com",
    val bucket: String = "",
    val prefix: String = "eta-speech/",
)

@Serializable
data class SpeechSettings(
    val asr: AsrProvider = AsrProvider.QWEN_REALTIME,
    val tts: TtsProvider = TtsProvider.NONE,
    val autoSpeak: Boolean = false,
    /** After a chat page answer completes, automatically read the final answer aloud; stored separately from the assistant overlay's auto-playback. */
    val chatAutoSpeak: Boolean = false,
    val language: String = "",
    val qwenAsr: QwenSpeechConfig = QwenSpeechConfig(),
    val qwenTts: QwenSpeechConfig = QwenSpeechConfig(),
    val doubaoAsr: DoubaoSpeechConfig = DoubaoSpeechConfig(),
    val doubaoTts: DoubaoSpeechConfig = DoubaoSpeechConfig(),
    val qwenVoice: String = "Cherry",
    val doubaoVoice: String = "zh_female_vv_uranus_bigtts",
    val geminiTts: GeminiTtsConfig = GeminiTtsConfig(),
    val minimaxTts: MiniMaxTtsConfig = MiniMaxTtsConfig(),
    val oss: SpeechOssConfig = SpeechOssConfig(),
    /** How long the assistant overlay pauses before auto-sending; 0 means send only when the user taps done or releases. */
    val autoSendSilenceMs: Int = DEFAULT_AUTO_SEND_SILENCE_MS,
    /** Conservatively correct the recognition result with the current conversation model before delivering it. */
    val refineTranscript: Boolean = false,
    /** Only controls Eta's own voice input; when disabled, the service configuration is retained so it can be re-enabled. */
    val recognitionEnabled: Boolean = true,
    val mimoAsr: MimoAsrConfig = MimoAsrConfig(),
    val mimoTts: MimoTtsConfig = MimoTtsConfig(),
    val deepseekAsr: DeepSeekAsrConfig = DeepSeekAsrConfig(),
    val deepseekTts: DeepSeekTtsConfig = DeepSeekTtsConfig(),
) {
    companion object {
        const val DEFAULT_AUTO_SEND_SILENCE_MS = 2_000
        val AUTO_SEND_SILENCE_OPTIONS = listOf(1_000, 1_500, 2_000, 3_000, 0)
    }
}

internal enum class SpeechCredentialField {
    QWEN_ASR, QWEN_TTS, DOUBAO_ASR, DOUBAO_TTS, GEMINI_TTS, MINIMAX_TTS, OSS_KEY_ID, OSS_KEY_SECRET,
    MIMO_ASR, MIMO_TTS, DEEPSEEK_ASR, DEEPSEEK_TTS,
}

/** Credentials are not part of Settings, so they are not leaked by normal config export or debug output. */
@Serializable
class SpeechCredentials(
    val qwenAsr: String = "",
    val qwenTts: String = "",
    val doubaoAsr: String = "",
    val doubaoTts: String = "",
    val ossAccessKeyId: String = "",
    val ossAccessKeySecret: String = "",
    val geminiTts: String = "",
    val minimaxTts: String = "",
    val mimoAsr: String = "",
    val mimoTts: String = "",
    val deepseekAsr: String = "",
    val deepseekTts: String = "",
) {
    internal fun withValue(field: SpeechCredentialField, value: String): SpeechCredentials = SpeechCredentials(
        qwenAsr = if (field == SpeechCredentialField.QWEN_ASR) value else qwenAsr,
        qwenTts = if (field == SpeechCredentialField.QWEN_TTS) value else qwenTts,
        doubaoAsr = if (field == SpeechCredentialField.DOUBAO_ASR) value else doubaoAsr,
        doubaoTts = if (field == SpeechCredentialField.DOUBAO_TTS) value else doubaoTts,
        ossAccessKeyId = if (field == SpeechCredentialField.OSS_KEY_ID) value else ossAccessKeyId,
        ossAccessKeySecret = if (field == SpeechCredentialField.OSS_KEY_SECRET) value else ossAccessKeySecret,
        geminiTts = if (field == SpeechCredentialField.GEMINI_TTS) value else geminiTts,
        minimaxTts = if (field == SpeechCredentialField.MINIMAX_TTS) value else minimaxTts,
        mimoAsr = if (field == SpeechCredentialField.MIMO_ASR) value else mimoAsr,
        mimoTts = if (field == SpeechCredentialField.MIMO_TTS) value else mimoTts,
        deepseekAsr = if (field == SpeechCredentialField.DEEPSEEK_ASR) value else deepseekAsr,
        deepseekTts = if (field == SpeechCredentialField.DEEPSEEK_TTS) value else deepseekTts,
    )

    override fun toString(): String = "SpeechCredentials(redacted)"
}
