package io.github.namanwtf.atom.data.model

import kotlinx.serialization.Serializable

/**
 * Model and reasoning configuration for the subagent.
 *
 * An empty [modelId] means it follows the main agent's currently active model; when a specific Model.id is set and the corresponding provider and model are still enabled,
 * the subagent preferentially runs with that model.
 */
@Serializable
internal data class SubagentSettings(
    val modelId: String? = null,
    val reasoningEffort: String? = null,
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)
}
