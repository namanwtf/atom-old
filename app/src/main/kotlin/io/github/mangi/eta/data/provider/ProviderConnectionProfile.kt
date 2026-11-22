package io.github.mangi.eta.data.provider

import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ProviderTypes
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Subscription credentials are bound to the official service address; user configuration cannot change their send target or billing channel. */
internal enum class ProviderConnectionProfile(
    val authMode: String,
    val baseUrl: String,
    val endpointMode: String,
    val sourceType: String,
) {
    OPENAI_CODEX(
        ProviderAuthModes.OPENAI_CHATGPT,
        "https://chatgpt.com/backend-api/codex",
        OpenAiEndpointMode.RESPONSES,
        ProviderSourceTypes.OPENAI,
    ),
    XAI_GROK(
        ProviderAuthModes.XAI_GROK,
        "https://cli-chat-proxy.grok.com/v1",
        OpenAiEndpointMode.RESPONSES,
        ProviderSourceTypes.XAI,
    );

    val providerType: String get() = ProviderTypes.OPENAI_COMPATIBLE
    val modelsUrl: String get() = "$baseUrl/models".toHttpUrl().newBuilder().apply {
        if (this@ProviderConnectionProfile == OPENAI_CODEX) {
            addQueryParameter("client_version", BuildConfig.VERSION_NAME.substringBefore('-').substringBefore('+'))
        }
    }.build().toString()
    val inferenceUrl: String get() = when (endpointMode) {
        OpenAiEndpointMode.RESPONSES -> "$baseUrl/responses"
        else -> "$baseUrl/chat/completions"
    }

    fun requireRequestTarget(url: HttpUrl) {
        require(url == modelsUrl.toHttpUrl() || url == inferenceUrl.toHttpUrl()) {
            "Subscription credentials can only be used with the corresponding provider's official model endpoints"
        }
    }

    companion object {
        fun forAuthMode(authMode: String): ProviderConnectionProfile? = when (authMode) {
            ProviderAuthModes.API_KEY -> null
            ProviderAuthModes.OPENAI_CHATGPT -> OPENAI_CODEX
            ProviderAuthModes.XAI_GROK -> XAI_GROK
            else -> error("Unsupported provider authentication method")
        }
    }
}
