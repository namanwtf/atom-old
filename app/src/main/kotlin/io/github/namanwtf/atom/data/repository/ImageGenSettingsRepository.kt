package io.github.namanwtf.atom.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.imagegen.ImageGenRuntimeConfig
import io.github.namanwtf.atom.core.AppStrings
import io.github.namanwtf.atom.data.datastore.SettingsDataStore
import io.github.namanwtf.atom.data.model.CustomProviderSetting
import io.github.namanwtf.atom.data.model.DoubaoImageConfig
import io.github.namanwtf.atom.data.model.GeminiEndpointMode
import io.github.namanwtf.atom.data.model.GeminiImageConfig
import io.github.namanwtf.atom.data.model.GeminiProviderSetting
import io.github.namanwtf.atom.data.model.ImageGenBackend
import io.github.namanwtf.atom.data.model.ImageGenCredentials
import io.github.namanwtf.atom.data.model.ImageGenSettings
import io.github.namanwtf.atom.data.model.OpenAiCompatibleProviderSetting
import io.github.namanwtf.atom.data.model.OpenAiImageConfig
import io.github.namanwtf.atom.data.model.ProviderAuthModes
import io.github.namanwtf.atom.data.model.ProviderSetting
import io.github.namanwtf.atom.data.model.ProviderSourceTypes
import io.github.namanwtf.atom.data.model.QwenImageConfig
import io.github.namanwtf.atom.data.model.SiliconFlowImageConfig
import io.github.namanwtf.atom.data.model.XaiImageConfig
import io.github.namanwtf.atom.data.provider.BuiltinProviders
import io.github.namanwtf.atom.data.provider.ProviderSourceRegistry
import java.net.InetAddress
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

internal class ImageGenCredentialsUnavailable : IllegalStateException(
    AppStrings.get(
        R.string.imagegen_error_credentials_unavailable,
        "AI image generation credentials are temporarily unavailable, please re-enter and save",
    ),
)

internal data class LinkedImageProviderOption(
    val id: String,
    val name: String,
    val hasApiKey: Boolean,
    val baseUrl: String,
)

internal object ImageGenSettingsRepository {
    private const val PREFS_NAME = "atom_image_gen_secrets"
    private const val KEY_CREDENTIALS = "credentials"
    private const val KEYSTORE_ALIAS = "atom_image_gen_credentials_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun settingsFlow(): Flow<ImageGenSettings> = SettingsDataStore.imageGenSettingsFlow()

    suspend fun settings(): ImageGenSettings = SettingsDataStore.imageGenSettings()

    suspend fun save(settings: ImageGenSettings) = SettingsDataStore.setImageGenSettings(settings)

    suspend fun credentials(context: Context): ImageGenCredentials = withContext(Dispatchers.IO) {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CREDENTIALS, null) ?: return@withContext ImageGenCredentials()
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            json.decodeFromString<ImageGenCredentials>(
                cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            throw ImageGenCredentialsUnavailable()
        }
    }

    suspend fun saveCredentials(context: Context, credentials: ImageGenCredentials) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(json.encodeToString(credentials).toByteArray(Charsets.UTF_8))
        check(
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CREDENTIALS, Base64.getEncoder().encodeToString(encrypted))
                .commit(),
        ) {
            AppStrings.get(R.string.imagegen_error_credentials_save_failed, "Failed to save AI image generation credentials")
        }
    }

    /** Returns the list of model providers usable for one-click reuse with the given image channel (filtering out OAuth accounts that don't support API keys). */
    suspend fun availableLinkedProviders(backend: ImageGenBackend): List<LinkedImageProviderOption> =
        withContext(Dispatchers.IO) {
            runCatching {
                ProviderRepository.allProviders()
                    .filter { provider ->
                        if (ProviderAuthModes.isOAuth(provider.authMode)) return@filter false
                        when (backend) {
                            ImageGenBackend.GEMINI -> provider is GeminiProviderSetting
                            ImageGenBackend.OPENAI_COMPATIBLE ->
                                provider is OpenAiCompatibleProviderSetting || provider is CustomProviderSetting
                            ImageGenBackend.XAI ->
                                ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.XAI ||
                                    provider.baseUrl.contains("api.x.ai", ignoreCase = true)
                            ImageGenBackend.QWEN ->
                                ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.BAILIAN ||
                                    provider.baseUrl.contains("dashscope", ignoreCase = true) ||
                                    provider.baseUrl.contains("maas.aliyuncs.com", ignoreCase = true)
                            ImageGenBackend.DOUBAO ->
                                (provider is OpenAiCompatibleProviderSetting || provider is CustomProviderSetting) &&
                                    (provider.baseUrl.contains("volces.com", ignoreCase = true) ||
                                        provider.name.contains("volcano", ignoreCase = true) ||
                                        provider.name.contains("doubao", ignoreCase = true))
                            ImageGenBackend.SILICONFLOW ->
                                ProviderSourceRegistry.resolve(provider) == ProviderSourceTypes.SILICONFLOW ||
                                    provider.baseUrl.contains("siliconflow", ignoreCase = true)
                        }
                    }
                    .map { provider ->
                        LinkedImageProviderOption(
                            id = provider.id,
                            name = provider.name,
                            hasApiKey = provider.apiKey.isNotBlank(),
                            baseUrl = provider.baseUrl.trim(),
                        )
                    }
            }.getOrDefault(emptyList())
        }

    suspend fun resolveRuntimeConfig(
        context: Context,
        settingsOverride: ImageGenSettings? = null,
        credentialsOverride: ImageGenCredentials? = null,
    ): ImageGenRuntimeConfig = withContext(Dispatchers.IO) {
        val settings = settingsOverride ?: runCatching { settings() }.getOrDefault(ImageGenSettings())
        val credentials = credentialsOverride ?: runCatching { credentials(context) }.getOrDefault(ImageGenCredentials())
        val allProviders = runCatching { ProviderRepository.allProviders() }.getOrDefault(emptyList())
        resolveRuntimeConfigPure(settings, credentials, allProviders)
    }

    /**
     * Credentials and the target address must be bound to the same connection configuration, so provider A's Key/Header is never sent to host B by mistake:
     * - Standalone mode (chose "fill in separately" or entered a non-empty standalone API key) uses the standalone config's address and region entirely, inheriting no associated provider's address or headers;
     * - Linked mode uses the selected associated provider's API Key, Base URL, CustomHeaders, and platform fields entirely.
     */
    internal fun resolveRuntimeConfigPure(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig = when (settings.backend) {
        ImageGenBackend.GEMINI -> resolveGemini(settings, credentials, allProviders)
        ImageGenBackend.OPENAI_COMPATIBLE -> resolveOpenAi(settings, credentials, allProviders)
        ImageGenBackend.XAI -> resolveXai(settings, credentials, allProviders)
        ImageGenBackend.DOUBAO -> resolveDoubao(settings, credentials, allProviders)
        ImageGenBackend.QWEN -> resolveQwen(settings, credentials, allProviders)
        ImageGenBackend.SILICONFLOW -> resolveSiliconFlow(settings, credentials, allProviders)
    }

    private fun resolveGemini(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.gemini
        val standaloneKey = credentials.geminiApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else {
            findLinkedProvider(cfg.linkedProviderId, allProviders) { providers ->
                providers.filterIsInstance<GeminiProviderSetting>().firstOrNull {
                    it.id == BuiltinProviders.GEMINI_ID && it.apiKey.isNotBlank()
                } ?: providers.filterIsInstance<GeminiProviderSetting>().firstOrNull { it.apiKey.isNotBlank() }
            } as? GeminiProviderSetting
        }

        val endpointMode = if (linked != null) {
            GeminiEndpointMode.normalize(linked.endpointMode)
        } else {
            GeminiEndpointMode.normalize(cfg.endpointMode)
        }
        val defaultUrl = if (endpointMode == GeminiEndpointMode.VERTEX_AI) {
            GeminiProviderSetting.DEFAULT_VERTEX_BASE_URL
        } else {
            GeminiProviderSetting.DEFAULT_BASE_URL
        }
        val baseUrl = if (linked != null) {
            linked.baseUrl.trim().ifEmpty { defaultUrl }
        } else {
            cfg.baseUrl.trim().ifEmpty { defaultUrl }
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.GEMINI,
            model = cfg.model.trim().ifEmpty { GeminiImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
            geminiEndpointMode = endpointMode,
            vertexProject = if (linked != null) linked.vertexProject.trim() else cfg.vertexProject.trim(),
            vertexLocation = if (linked != null) {
                linked.vertexLocation.trim().ifEmpty { GeminiProviderSetting.DEFAULT_VERTEX_LOCATION }
            } else {
                cfg.vertexLocation.trim().ifEmpty { GeminiProviderSetting.DEFAULT_VERTEX_LOCATION }
            },
        )
    }

    private fun resolveOpenAi(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.openAi
        val standaloneKey = credentials.openAiApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else {
            findLinkedProvider(cfg.linkedProviderId, allProviders) { providers ->
                providers.firstOrNull {
                    it.id == BuiltinProviders.OPENAI_ID &&
                        !ProviderAuthModes.isOAuth(it.authMode) &&
                        it.apiKey.isNotBlank()
                } ?: providers.firstOrNull {
                    ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.OPENAI &&
                        !ProviderAuthModes.isOAuth(it.authMode) &&
                        it.apiKey.isNotBlank()
                }
            }
        }
        val baseUrl = if (linked != null) {
            linked.baseUrl.trim().ifEmpty { OpenAiImageConfig.DEFAULT_BASE_URL }
        } else {
            cfg.baseUrl.trim().ifEmpty { OpenAiImageConfig.DEFAULT_BASE_URL }
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.OPENAI_COMPATIBLE,
            model = cfg.model.trim().ifEmpty { OpenAiImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
            openAiQuality = cfg.quality.trim().ifEmpty { "auto" },
        )
    }

    private fun resolveXai(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.xai
        val standaloneKey = credentials.xaiApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else {
            findLinkedProvider(cfg.linkedProviderId, allProviders) { providers ->
                providers.firstOrNull {
                    it.id == BuiltinProviders.XAI_ID &&
                        !ProviderAuthModes.isOAuth(it.authMode) &&
                        it.apiKey.isNotBlank()
                } ?: providers.firstOrNull {
                    ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.XAI &&
                        !ProviderAuthModes.isOAuth(it.authMode) &&
                        it.apiKey.isNotBlank()
                }
            }
        }
        val baseUrl = if (linked != null) {
            linked.baseUrl.trim().ifEmpty { XaiImageConfig.DEFAULT_BASE_URL }
        } else {
            cfg.baseUrl.trim().ifEmpty { XaiImageConfig.DEFAULT_BASE_URL }
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.XAI,
            model = cfg.model.trim().ifEmpty { XaiImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
        )
    }

    private fun resolveDoubao(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.doubao
        val standaloneKey = credentials.doubaoApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else findLinkedProvider(cfg.linkedProviderId, allProviders) { null }
        val baseUrl = if (linked != null) {
            linked.baseUrl.trim().ifEmpty { DoubaoImageConfig.DEFAULT_BASE_URL }
        } else {
            cfg.baseUrl.trim().ifEmpty { DoubaoImageConfig.DEFAULT_BASE_URL }
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.DOUBAO,
            model = cfg.model.trim().ifEmpty { DoubaoImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
            watermark = cfg.watermark,
        )
    }

    private fun resolveQwen(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.qwen
        val standaloneKey = credentials.qwenApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else {
            findLinkedProvider(cfg.linkedProviderId, allProviders) { providers ->
                providers.firstOrNull {
                    it.id == BuiltinProviders.BAILIAN_ID && it.apiKey.isNotBlank()
                } ?: providers.firstOrNull {
                    ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.BAILIAN && it.apiKey.isNotBlank()
                }
            }
        }
        // Standalone mode determines the address from the user-selected region and custom domain, never inheriting the associated Beijing provider's address.
        val baseUrl = if (linked != null) {
            normalizeDashScopeEndpoint(linked.baseUrl, cfg.defaultEndpoint())
        } else {
            normalizeDashScopeEndpoint(cfg.baseUrl, cfg.defaultEndpoint())
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.QWEN,
            model = cfg.model.trim().ifEmpty { QwenImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
            watermark = cfg.watermark,
            promptExtend = cfg.promptExtend,
        )
    }

    private fun resolveSiliconFlow(
        settings: ImageGenSettings,
        credentials: ImageGenCredentials,
        allProviders: List<ProviderSetting>,
    ): ImageGenRuntimeConfig {
        val cfg = settings.siliconFlow
        val standaloneKey = credentials.siliconFlowApiKey.trim()
        val useStandalone = cfg.linkedProviderId.trim() == ImageGenSettings.STANDALONE_LINK_ID || standaloneKey.isNotEmpty()
        val linked = if (useStandalone) null else {
            findLinkedProvider(cfg.linkedProviderId, allProviders) { providers ->
                providers.firstOrNull {
                    it.id == BuiltinProviders.SILICONFLOW_ID && it.apiKey.isNotBlank()
                } ?: providers.firstOrNull {
                    ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.SILICONFLOW && it.apiKey.isNotBlank()
                }
            }
        }
        val baseUrl = if (linked != null) {
            linked.baseUrl.trim().ifEmpty { SiliconFlowImageConfig.DEFAULT_BASE_URL }
        } else {
            cfg.baseUrl.trim().ifEmpty { SiliconFlowImageConfig.DEFAULT_BASE_URL }
        }
        return ImageGenRuntimeConfig(
            enabled = settings.enabled,
            backend = ImageGenBackend.SILICONFLOW,
            model = cfg.model.trim().ifEmpty { SiliconFlowImageConfig.DEFAULT_MODEL },
            baseUrl = baseUrl,
            apiKey = if (linked != null) linked.apiKey.trim() else standaloneKey,
            defaultAspectRatio = settings.defaultAspectRatio,
            defaultResolution = settings.defaultResolution,
            customHeaders = linked?.customHeaders.orEmpty(),
        )
    }

    private fun findLinkedProvider(
        linkedProviderId: String,
        allProviders: List<ProviderSetting>,
        autoMatch: (List<ProviderSetting>) -> ProviderSetting?,
    ): ProviderSetting? {
        val trimmed = linkedProviderId.trim()
        if (trimmed == ImageGenSettings.STANDALONE_LINK_ID) return null
        if (trimmed.isNotEmpty()) {
            return allProviders.firstOrNull {
                it.id == trimmed && !ProviderAuthModes.isOAuth(it.authMode)
            }
        }
        return autoMatch(allProviders)
    }

    /** Validate an endpoint address that carries credentials: public internet must use HTTPS, and HTTP is allowed only for localhost and LAN private networks. */
    internal fun validateEndpointUrl(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) {
            return AppStrings.get(R.string.imagegen_error_url_empty, "Endpoint URL cannot be empty")
        }
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: return AppStrings.get(R.string.imagegen_error_url_invalid, "Endpoint URL format is invalid")
        val scheme = uri.scheme?.lowercase()
            ?: return AppStrings.get(R.string.imagegen_error_url_missing_scheme, "Endpoint URL is missing the scheme(https://)")
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return AppStrings.get(R.string.imagegen_error_url_missing_host, "Endpoint URL is missing a valid hostname")
        if (scheme == "https") return null
        if (scheme == "http" && isLocalOrPrivateHost(host)) return null
        return AppStrings.get(
            R.string.imagegen_error_url_https_required,
            "A public endpoint carrying an API Key must use HTTPS",
        )
    }

    private fun isLocalOrPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.endsWith(".local")) return true
        val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
    }

    /** Normalize an Alibaba Bailian OpenAI-compatible endpoint to the DashScope native API root domain, preserving the user's dedicated workspace domain. */
    internal fun normalizeDashScopeEndpoint(raw: String, fallback: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return fallback.trimEnd('/')
        val withoutCompat = trimmed
            .removeSuffix("/compatible-mode/v1")
            .removeSuffix("/compatible-mode")
            .removeSuffix("/api/v1/services/aigc/multimodal-generation/generation")
            .removeSuffix("/api/v1")
            .trimEnd('/')
        return withoutCompat.ifEmpty { fallback.trimEnd('/') }
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}
