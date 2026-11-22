package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Configuration for auto-generating conversation titles.
 *
 * An empty [modelId] means follow the model used by the current conversation (or the default model); when a specific [Model.id] is set and the corresponding
 * provider and model are still enabled, prefer that model for title generation, making it easy to lower token consumption with a lightweight small model.
 * An empty [reasoningEffort] means automatically pick the lowest available reasoning level (preferring to disable reasoning).
 */
@Serializable
internal data class ConversationTitleSettings(
    val enabled: Boolean = true,
    val modelId: String? = null,
    val reasoningEffort: String? = null,
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)
}
