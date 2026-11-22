package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechCredentialsUnavailable
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SpeechErrorCode {
    CONFIGURATION, CREDENTIALS, PERMISSION, AUDIO, NETWORK, TIMEOUT, AUTHENTICATION,
    RATE_LIMITED, SERVER, PROTOCOL, NO_SPEECH, BACKPRESSURE, STORAGE,
}

internal class SpeechFailure(val code: SpeechErrorCode, val userMessage: String) : IOException(code.name)

internal fun Throwable.speechFailure(): SpeechFailure = when (this) {
    is SpeechFailure -> this
    is SpeechCredentialsUnavailable -> SpeechFailure(
        SpeechErrorCode.CREDENTIALS,
        AppStrings.get(R.string.voice_i18n_credentials_decrypt_failed, "Voice credentials cannot be decrypted, please re-enter and save"),
    )
    is SecurityException -> SpeechFailure(
        SpeechErrorCode.PERMISSION,
        AppStrings.get(R.string.voice_i18n_mic_permission_required, "Please allow Eta to use the microphone"),
    )
    is kotlinx.coroutines.TimeoutCancellationException -> SpeechFailure(
        SpeechErrorCode.TIMEOUT,
        AppStrings.get(R.string.voice_i18n_service_timeout, "Voice service response timed out, please retry"),
    )
    is IOException -> SpeechFailure(
        SpeechErrorCode.NETWORK,
        AppStrings.get(R.string.voice_i18n_network_unavailable, "Cannot connect to the voice service, please check your network"),
    )
    else -> SpeechFailure(
        SpeechErrorCode.PROTOCOL,
        AppStrings.get(R.string.voice_i18n_service_unreadable_data, "The voice service returned data that cannot be processed"),
    )
}

internal fun validateSpeechSettings(
    settings: SpeechSettings,
    secrets: SpeechCredentials,
    synthesis: Boolean,
    hasProviderDeepSeekKey: Boolean = false,
) {
    if (!synthesis && !settings.recognitionEnabled) return
    fun required(value: String, nameResId: Int, fallbackName: String) {
        if (value.isBlank()) {
            val name = AppStrings.get(nameResId, fallbackName)
            throw SpeechFailure(
                SpeechErrorCode.CONFIGURATION,
                AppStrings.get(R.string.voice_i18n_config_required_field, "Please configure %1\$s", name),
            )
        }
    }
    if (synthesis) {
        when (settings.tts) {
            TtsProvider.NONE -> throw SpeechFailure(
                SpeechErrorCode.CONFIGURATION,
                AppStrings.get(R.string.voice_i18n_config_tts_required, "Please configure voice read-aloud in settings first"),
            )
            TtsProvider.QWEN -> {
                required(secrets.qwenTts, R.string.voice_i18n_field_qwen_tts_api_key, "Qwen read-aloud API Key")
                required(settings.qwenVoice, R.string.voice_i18n_field_voice, "Voice")
                speechBaseUrl(settings.qwenTts.endpoint())
            }
            TtsProvider.DOUBAO -> {
                required(secrets.doubaoTts, R.string.voice_i18n_field_doubao_tts_credentials, "Doubao read-aloud credentials")
                required(settings.doubaoVoice, R.string.voice_i18n_field_voice, "Voice")
                speechBaseUrl(settings.doubaoTts.baseUrl)
                if (settings.doubaoTts.legacyAuth) {
                    required(settings.doubaoTts.appId, R.string.voice_i18n_field_doubao_app_id, "Doubao App ID")
                }
            }
            TtsProvider.GEMINI -> {
                required(secrets.geminiTts, R.string.voice_i18n_field_gemini_tts_api_key, "Gemini read-aloud API Key")
                required(settings.geminiTts.voice, R.string.voice_i18n_field_voice, "Voice")
                speechBaseUrl(settings.geminiTts.baseUrl)
                if (settings.geminiTts.style.length > 500) {
                    throw SpeechFailure(
                        SpeechErrorCode.CONFIGURATION,
                        AppStrings.get(R.string.voice_i18n_config_style_too_long, "Keep the reading style within 500 characters"),
                    )
                }
            }
            TtsProvider.MINIMAX -> {
                required(secrets.minimaxTts, R.string.voice_i18n_field_minimax_tts_api_key, "MiniMax read-aloud API Key")
                required(settings.minimaxTts.voice, R.string.voice_i18n_field_voice, "Voice")
                speechBaseUrl(settings.minimaxTts.baseUrl)
                if (settings.minimaxTts.speed !in 0.5f..2f) {
                    throw SpeechFailure(
                        SpeechErrorCode.CONFIGURATION,
                        AppStrings.get(R.string.voice_i18n_config_minimax_speed_range, "MiniMax speed must be within 0.5~2 times"),
                    )
                }
            }
            TtsProvider.MIMO -> {
                required(secrets.mimoTts, R.string.voice_i18n_field_mimo_tts_api_key, "MiMo read-aloud API Key")
                required(settings.mimoTts.voice, R.string.voice_i18n_field_voice, "Voice")
                speechBaseUrl(settings.mimoTts.baseUrl)
                if (settings.mimoTts.style.length > 500) {
                    throw SpeechFailure(
                        SpeechErrorCode.CONFIGURATION,
                        AppStrings.get(R.string.voice_i18n_config_style_too_long, "Keep the reading style within 500 characters"),
                    )
                }
            }
            TtsProvider.DEEPSEEK -> {
                if (!hasProviderDeepSeekKey) {
                    required(secrets.deepseekTts, R.string.voice_i18n_field_deepseek_tts_api_key, "DeepSeek read-aloud API Key")
                }
                required(settings.deepseekTts.voice, R.string.voice_i18n_field_voice, "Voice")
                speechBaseUrl(settings.deepseekTts.baseUrl)
            }
        }
    } else when (settings.asr) {
        AsrProvider.MIMO -> {
            required(secrets.mimoAsr, R.string.voice_i18n_field_mimo_asr_api_key, "MiMo recognition API Key")
            speechBaseUrl(settings.mimoAsr.baseUrl)
        }
        AsrProvider.DEEPSEEK -> {
            if (!hasProviderDeepSeekKey) {
                required(secrets.deepseekAsr, R.string.voice_i18n_field_deepseek_asr_api_key, "DeepSeek recognition API Key")
            }
            speechBaseUrl(settings.deepseekAsr.baseUrl)
        }
        AsrProvider.DOUBAO -> {
            required(secrets.doubaoAsr, R.string.voice_i18n_field_doubao_asr_credentials, "Doubao recognition credentials")
            speechBaseUrl(settings.doubaoAsr.baseUrl)
            if (settings.doubaoAsr.legacyAuth) {
                required(settings.doubaoAsr.appId, R.string.voice_i18n_field_doubao_app_id, "Doubao App ID")
            }
        }
        AsrProvider.QWEN_REALTIME,
        AsrProvider.QWEN_FLASH,
        AsrProvider.QWEN_FILE,
        -> {
            required(secrets.qwenAsr, R.string.voice_i18n_field_qwen_asr_api_key, "Qwen recognition API Key")
            speechBaseUrl(settings.qwenAsr.endpoint())
            if (settings.asr == AsrProvider.QWEN_FILE) {
                required(settings.oss.bucket, R.string.voice_i18n_field_oss_bucket, "OSS Bucket")
                required(settings.oss.region, R.string.voice_i18n_field_oss_region, "OSS region")
                required(secrets.ossAccessKeyId, R.string.voice_i18n_field_oss_access_key_id, "OSS AccessKey ID")
                required(
                    secrets.ossAccessKeySecret,
                    R.string.voice_i18n_field_oss_access_key_secret,
                    "OSS AccessKey Secret",
                )
                speechBaseUrl(settings.oss.endpoint)
                if (!Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]").matches(settings.oss.bucket) ||
                    settings.oss.prefix.split('/').any { it == ".." || it == "." }
                ) throw SpeechFailure(
                    SpeechErrorCode.CONFIGURATION,
                    AppStrings.get(R.string.voice_i18n_config_oss_bucket_prefix_invalid, "please check OSS Bucket and directory prefix"),
                )
            }
        }
    }
}

internal fun speechBaseUrl(value: String): String {
    val url = value.trim().toHttpUrlOrNull()
    if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null
    ) throw SpeechFailure(
        SpeechErrorCode.CONFIGURATION,
        AppStrings.get(R.string.voice_i18n_config_base_url_invalid, "The service URL must be an HTTPS address without authentication info or query parameters"),
    )
    return url.toString().trimEnd('/')
}
