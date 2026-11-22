package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Model and reasoning-level configuration for the global conversation overlay assistant (invoked via power key / system assistant).
 *
 * An empty [modelId] means follow the current main model; when a specific [Model.id] is set and the corresponding provider and model are still enabled,
 * the overlay assistant preferentially runs with that model. An empty [reasoningEffort] means follow the default reasoning configuration.
 */
@Serializable
internal data class AssistantOverlaySettings(
    val modelId: String? = null,
    val reasoningEffort: String? = null,
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = ReasoningEffort.fromWireValue(reasoningEffort)
}
