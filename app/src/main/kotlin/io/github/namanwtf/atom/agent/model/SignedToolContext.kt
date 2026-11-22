package io.github.namanwtf.atom.agent.model

import org.json.JSONArray

/** Keep the request prefix until the signed tool chain completes, so compression or catalog refresh does not break the next turn's round-trip. */
internal object SignedToolContext {
    fun hasPendingResponse(messages: JSONArray): Boolean =
        AnthropicEphemeralState.hasPendingToolResponse(messages) ||
            GeminiEphemeralState.hasPendingToolResponse(messages)

    fun compactionFailure(messages: JSONArray): AgentModelFailure =
        if (GeminiEphemeralState.hasPendingToolResponse(messages)) {
            AgentModelFailure(
                "GEMINI_THINKING_CONTEXT_LOCKED",
                false,
                "The current Gemini tool chain is not yet complete; the signed context cannot be compacted before tool results are submitted.",
            )
        } else {
            AgentModelFailure(
                "ANTHROPIC_THINKING_CONTEXT_LOCKED",
                false,
                "The reasoning signature of the current Anthropic tool turn is bound to the original context; the context cannot be compacted before tool results are submitted.",
            )
        }
}
