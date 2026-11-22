package io.github.mangi.eta.data.model

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import kotlinx.serialization.Serializable

@Serializable
internal enum class ImageGenBackend(val wireValue: String) {
    GEMINI("gemini"),
    OPENAI_COMPATIBLE("openai_compatible"),
    XAI("xai"),
    DOUBAO("doubao"),
    QWEN("qwen"),
    SILICONFLOW("siliconflow");

    companion object {
        fun fromWireValue(value: String?): ImageGenBackend =
            entries.firstOrNull { it.wireValue.equals(value?.trim(), ignoreCase = true) } ?: GEMINI
    }
}

@Serializable
internal enum class ImageGenAspectRatio(
    val wireValue: String,
    @StringRes val displayLabelRes: Int,
    private val fallbackDisplayLabel: String,
) {
    AUTO("auto", R.string.imagegen_aspect_ratio_auto, "Auto"),
    RATIO_1_1("1:1", R.string.imagegen_aspect_ratio_1_1, "1:1 square"),
    RATIO_16_9("16:9", R.string.imagegen_aspect_ratio_16_9, "16:9 landscape"),
    RATIO_9_16("9:16", R.string.imagegen_aspect_ratio_9_16, "9:16 portrait"),
    RATIO_4_3("4:3", R.string.imagegen_aspect_ratio_4_3, "4:3 landscape"),
    RATIO_3_4("3:4", R.string.imagegen_aspect_ratio_3_4, "3:4 portrait"),
    RATIO_3_2("3:2", R.string.imagegen_aspect_ratio_3_2, "3:2 camera landscape"),
    RATIO_2_3("2:3", R.string.imagegen_aspect_ratio_2_3, "2:3 poster portrait");

    val displayLabel: String
        get() = AppStrings.get(displayLabelRes, fallbackDisplayLabel)

    companion object {
        fun fromWireValue(value: String?): ImageGenAspectRatio {
            val normalized = value?.trim()?.lowercase() ?: return AUTO
            return entries.firstOrNull { it.wireValue == normalized } ?: AUTO
        }
    }
}

@Serializable
internal enum class ImageGenResolution(
    val wireValue: String,
    @StringRes val displayLabelRes: Int,
    private val fallbackDisplayLabel: String,
) {
    R_1K("1K", R.string.imagegen_resolution_1k, "1K standard"),
    R_2K("2K", R.string.imagegen_resolution_2k, "2K HD"),
    R_4K("4K", R.string.imagegen_resolution_4k, "4K ultra");

    val displayLabel: String
        get() = AppStrings.get(displayLabelRes, fallbackDisplayLabel)

    companion object {
        fun fromWireValue(value: String?): ImageGenResolution {
            val normalized = value?.trim()?.uppercase() ?: return R_1K
            return when (normalized) {
                "1K", "STANDARD", "LOW", "MEDIUM" -> R_1K
                "2K", "HD", "HIGH" -> R_2K
                "4K", "ULTRA" -> R_4K
                else -> R_1K
            }
        }
    }
}

internal data class ImageGenModelPreset(
    val id: String,
    internal val rawLabel: String,
    @StringRes val noteRes: Int,
    private val fallbackNote: String,
    @StringRes val labelRes: Int? = null,
) {
    val label: String
        get() = labelRes?.let { AppStrings.get(it, rawLabel) } ?: rawLabel

    val note: String
        get() = AppStrings.get(noteRes, fallbackNote)
}

internal object ImageGenModelPresets {
    val GEMINI: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "gemini-nano-banana-2.1",
            rawLabel = "Nano Banana 2.1",
            noteRes = R.string.imagegen_preset_note_gemini_nano_banana_2_1,
            fallbackNote = "Top recommendation · 1K/2K/4K consistent with multi-image",
        ),
        ImageGenModelPreset(
            id = "gemini-3.1-flash-lite-image",
            rawLabel = "Nano Banana 2 Lite",
            noteRes = R.string.imagegen_preset_note_gemini_3_1_flash_lite,
            fallbackNote = "Ultra-fast and low-cost · 1K resolution only",
        ),
        ImageGenModelPreset(
            id = "gemini-3.1-flash-image",
            rawLabel = "Nano Banana 2",
            noteRes = R.string.imagegen_preset_note_gemini_3_1_flash,
            fallbackNote = "Supports 1K~4K and multiple reference images",
        ),
        ImageGenModelPreset(
            id = "gemini-3-pro-image",
            rawLabel = "Nano Banana Pro",
            noteRes = R.string.imagegen_preset_note_gemini_3_pro,
            fallbackNote = "Professional layout and complex instruction reasoning",
        ),
        ImageGenModelPreset(
            id = "gemini-2.5-flash-image",
            rawLabel = "Nano Banana 1",
            noteRes = R.string.imagegen_preset_note_gemini_2_5_flash,
            fallbackNote = "Fixed-resolution classic model",
        ),
    )

    val OPENAI_COMPATIBLE: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "gpt-image-2.6-flare",
            rawLabel = "GPT-Image 2.6 Flare",
            noteRes = R.string.imagegen_preset_note_gpt_2_6_flare,
            fallbackNote = "OpenAI fast high-quality image generation",
        ),
        ImageGenModelPreset(
            id = "gpt-image-2.6-sunburst",
            rawLabel = "GPT-Image 2.6 Sunburst",
            noteRes = R.string.imagegen_preset_note_gpt_2_6_sunburst,
            fallbackNote = "OpenAI fine editing and high fidelity",
        ),
        ImageGenModelPreset(
            id = "gpt-image-2.5-flare",
            rawLabel = "GPT-Image 2.5 Flare",
            noteRes = R.string.imagegen_preset_note_gpt_2_5_flare,
            fallbackNote = "OpenAI 2.5 fast flagship",
        ),
        ImageGenModelPreset(
            id = "gpt-image-2.5-sunburst",
            rawLabel = "GPT-Image 2.5 Sunburst",
            noteRes = R.string.imagegen_preset_note_gpt_2_5_sunburst,
            fallbackNote = "OpenAI 2.5 fine editing",
        ),
        ImageGenModelPreset(
            id = "gpt-image-1.5",
            rawLabel = "GPT-Image 1.5",
            noteRes = R.string.imagegen_preset_note_gpt_1_5,
            fallbackNote = "Broadly compatible version",
        ),
    )

    val XAI: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "grok-imagine-image-2.0",
            rawLabel = "Grok Imagine 2.0",
            noteRes = R.string.imagegen_preset_note_xai_grok_2_0,
            fallbackNote = "xAI image generation and editing with up to 5 reference images",
        ),
    )

    val DOUBAO: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "doubao-seedream-5-5-261028",
            rawLabel = "Seedream 5.5",
            noteRes = R.string.imagegen_preset_note_doubao_5_5,
            fallbackNote = "Latest flagship · 1K/2K realism and native transparent background",
        ),
        ImageGenModelPreset(
            id = "doubao-seedream-5-0-pro-260628",
            rawLabel = "Seedream 5.0 Pro",
            noteRes = R.string.imagegen_preset_note_doubao_5_0_pro,
            fallbackNote = "1K/2K precise editing and high detail",
        ),
        ImageGenModelPreset(
            id = "doubao-seedream-5-0-flash-260915",
            rawLabel = "Seedream 5.0 Flash",
            noteRes = R.string.imagegen_preset_note_doubao_5_0_flash,
            fallbackNote = "1K/2K fast response",
        ),
        ImageGenModelPreset(
            id = "doubao-seedream-5-0-lite-260128",
            rawLabel = "Seedream 5.0 Lite",
            noteRes = R.string.imagegen_preset_note_doubao_5_0_lite,
            fallbackNote = "Supports 2K/4K and high-resolution output",
        ),
        ImageGenModelPreset(
            id = "doubao-seedream-4-5-251128",
            rawLabel = "Seedream 4.5",
            noteRes = R.string.imagegen_preset_note_doubao_4_5,
            fallbackNote = "Classic 2K/4K high quality",
        ),
    )

    val QWEN: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "qwen-image-2.0-pro",
            rawLabel = "Qwen-Image 2.0 Pro",
            noteRes = R.string.imagegen_preset_note_qwen_2_0_pro,
            fallbackNote = "Top recommendation · strong Chinese typography and realism",
        ),
        ImageGenModelPreset(
            id = "qwen-image-2.0",
            rawLabel = "Qwen-Image 2.0",
            noteRes = R.string.imagegen_preset_note_qwen_2_0,
            fallbackNote = "Accelerated · balanced speed and quality",
        ),
    )

    val SILICONFLOW: List<ImageGenModelPreset> = listOf(
        ImageGenModelPreset(
            id = "Qwen/Qwen-Image",
            rawLabel = "Qwen-Image",
            noteRes = R.string.imagegen_preset_note_sf_qwen_image,
            fallbackNote = "SiliconFlow · excellent overall and Chinese performance",
        ),
        ImageGenModelPreset(
            id = "Qwen/Qwen-Image-Edit-2509",
            rawLabel = "Qwen-Image-Edit",
            noteRes = R.string.imagegen_preset_note_sf_qwen_edit,
            fallbackNote = "SiliconFlow · supports image-to-image editing",
        ),
        ImageGenModelPreset(
            id = "Kwai-Kolors/Kolors",
            rawLabel = "Kolors",
            noteRes = R.string.imagegen_preset_note_sf_kolors,
            fallbackNote = "SiliconFlow · cost-effective text-to-image",
            labelRes = R.string.imagegen_preset_label_kolors,
        ),
        ImageGenModelPreset(
            id = "black-forest-labs/FLUX.1-schnell",
            rawLabel = "FLUX.1 Schnell",
            noteRes = R.string.imagegen_preset_note_sf_flux_schnell,
            fallbackNote = "SiliconFlow · ultra-fast text-to-image",
        ),
    )

    fun forBackend(backend: ImageGenBackend): List<ImageGenModelPreset> = when (backend) {
        ImageGenBackend.GEMINI -> GEMINI
        ImageGenBackend.OPENAI_COMPATIBLE -> OPENAI_COMPATIBLE
        ImageGenBackend.XAI -> XAI
        ImageGenBackend.DOUBAO -> DOUBAO
        ImageGenBackend.QWEN -> QWEN
        ImageGenBackend.SILICONFLOW -> SILICONFLOW
    }
}

@Serializable
internal data class GeminiImageConfig(
    /** When empty, auto-match a configured built-in Gemini provider; explicitly set to [ImageGenSettings.STANDALONE_LINK_ID] to indicate a standalone configuration. */
    val linkedProviderId: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = "",
    val endpointMode: String = GeminiEndpointMode.AI_STUDIO,
    val vertexProject: String = "",
    val vertexLocation: String = GeminiProviderSetting.DEFAULT_VERTEX_LOCATION,
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-nano-banana-2.1"
    }
}

@Serializable
internal data class OpenAiImageConfig(
    val linkedProviderId: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val quality: String = "auto",
) {
    companion object {
        const val DEFAULT_MODEL = "gpt-image-2.6-flare"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}

@Serializable
internal data class XaiImageConfig(
    val linkedProviderId: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_MODEL = "grok-imagine-image-2.0"
        const val DEFAULT_BASE_URL = "https://api.x.ai/v1"
    }
}

@Serializable
internal data class DoubaoImageConfig(
    val linkedProviderId: String = ImageGenSettings.STANDALONE_LINK_ID,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
    val watermark: Boolean = false,
) {
    companion object {
        const val DEFAULT_MODEL = "doubao-seedream-5-5-261028"
        const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    }
}

@Serializable
internal data class QwenImageConfig(
    val linkedProviderId: String = "",
    val region: SpeechRegion = SpeechRegion.BEIJING,
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = "",
    val promptExtend: Boolean = true,
    val watermark: Boolean = false,
) {
    fun defaultEndpoint(): String =
        if (region == SpeechRegion.BEIJING) "https://dashscope.aliyuncs.com"
        else "https://dashscope-intl.aliyuncs.com"

    companion object {
        const val DEFAULT_MODEL = "qwen-image-2.0-pro"
    }
}

@Serializable
internal data class SiliconFlowImageConfig(
    val linkedProviderId: String = "",
    val model: String = DEFAULT_MODEL,
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        const val DEFAULT_MODEL = "Qwen/Qwen-Image"
        const val DEFAULT_BASE_URL = "https://api.siliconflow.cn/v1"
    }
}

@Serializable
internal data class ImageGenSettings(
    val enabled: Boolean = false,
    val backend: ImageGenBackend = ImageGenBackend.GEMINI,
    val defaultAspectRatio: ImageGenAspectRatio = ImageGenAspectRatio.AUTO,
    val defaultResolution: ImageGenResolution = ImageGenResolution.R_1K,
    val gemini: GeminiImageConfig = GeminiImageConfig(),
    val openAi: OpenAiImageConfig = OpenAiImageConfig(),
    val xai: XaiImageConfig = XaiImageConfig(),
    val doubao: DoubaoImageConfig = DoubaoImageConfig(),
    val qwen: QwenImageConfig = QwenImageConfig(),
    val siliconFlow: SiliconFlowImageConfig = SiliconFlowImageConfig(),
) {
    companion object {
        const val STANDALONE_LINK_ID = "__standalone__"
    }
}

/** Standalone credentials are not written in plaintext to the normal DataStore; they are uniformly encrypted with AndroidKeyStore AES-GCM. */
@Serializable
internal data class ImageGenCredentials(
    val geminiApiKey: String = "",
    val openAiApiKey: String = "",
    val xaiApiKey: String = "",
    val doubaoApiKey: String = "",
    val qwenApiKey: String = "",
    val siliconFlowApiKey: String = "",
) {
    override fun toString(): String = "ImageGenCredentials(redacted)"
}
