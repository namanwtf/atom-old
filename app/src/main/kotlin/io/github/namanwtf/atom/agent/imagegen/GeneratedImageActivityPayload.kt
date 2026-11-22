package io.github.namanwtf.atom.agent.imagegen

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * AI image-generation display payload transported through the existing `ToolActivityMessageUi.command` / `AgentEvent.ToolFinished.command` channels,
 * with zero protocol breakage and zero database migration, while fully supporting live streaming, history recovery, and archive replay.
 */
@Immutable
@Serializable
internal data class GeneratedImageActivityPayload(
    val version: Int = VERSION,
    val prompt: String = "",
    val aspectRatio: String = "auto",
    val resolution: String = "",
    val model: String = "",
    val backend: String = "",
    val requestedCount: Int = 1,
    val referenceCount: Int = 0,
    val images: List<GeneratedImageItem> = emptyList(),
    val errorMessage: String? = null,
) {
    @Immutable
    @Serializable
    data class GeneratedImageItem(
        val id: String,
        val filePath: String,
        val mimeType: String = "image/png",
        val width: Int = 0,
        val height: Int = 0,
    )

    fun encode(): String = json.encodeToString(this)

    companion object {
        const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String?): GeneratedImageActivityPayload? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) return null
            return runCatching {
                json.decodeFromString<GeneratedImageActivityPayload>(trimmed)
            }.getOrNull()
        }

        fun initialFromArguments(argumentsJson: String): GeneratedImageActivityPayload? =
            runCatching {
                val args = JSONObject(argumentsJson)
                val prompt = args.optString("prompt").trim().take(400)
                val ratio = args.optString("aspect_ratio").trim().ifEmpty { "auto" }
                val res = args.optString("resolution").trim()
                val count = args.optInt("count", 1).coerceIn(1, 4)
                val refCount = args.optJSONArray("reference_images")?.length() ?: 0
                GeneratedImageActivityPayload(
                    prompt = prompt,
                    aspectRatio = ratio,
                    resolution = res,
                    requestedCount = count,
                    referenceCount = refCount,
                )
            }.getOrNull()
    }
}
