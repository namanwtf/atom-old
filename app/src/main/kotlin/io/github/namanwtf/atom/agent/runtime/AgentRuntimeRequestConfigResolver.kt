package io.github.namanwtf.atom.agent.runtime

import io.github.namanwtf.atom.agent.model.AgentModelClient
import io.github.namanwtf.atom.data.model.ProviderAuthModes

internal object AgentRuntimeRequestConfigResolver {
    fun constrainPresentation(
        request: AgentRuntimeWire.RunRequest,
        isAtomProcess: Boolean,
    ): AgentRuntimeWire.RunRequest {
        val supportedEntry = request.handoff?.source in setOf(
            AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
            AgentRuntimeWire.ATOM_VOICE_HANDOFF_SOURCE,
        )
        if (!request.config.supportsSearchSuggestions || isAtomProcess && supportedEntry) return request
        return request.copy(config = request.config.copy(supportsSearchSuggestions = false))
    }

    fun requiresRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        usesDefaultRuntimeConfig(request) || ProviderAuthModes.isOAuth(request.config.authMode)

    fun usesDefaultRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        request.handoff?.source == AgentRuntimeWire.ATOM_VOICE_HANDOFF_SOURCE

    /**
     * Replace only the model and credentials in the entry config with the Runtime side's; tool switches keep the entry request's values,
     * and are then still intersected with AgentRuntimePolicy and local permissions. The model config itself does not carry tool switches;
     * replacing it wholesale would leave the default-off terminal, sensitive-read, and sensitive-action tools permanently unavailable from the voice entry.
     */
    fun applyRuntimeConfig(
        request: AgentRuntimeWire.RunRequest,
        runtimeConfig: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest {
        if (!requiresRuntimeConfig(request)) return request
        val entry = request.config
        val preserveReasoningChoice = !usesDefaultRuntimeConfig(request)
        if (preserveReasoningChoice) {
            require(
                runtimeConfig.providerId == entry.providerId &&
                    runtimeConfig.authMode == entry.authMode && runtimeConfig.model == entry.model
            ) { "Account sign-in configuration changed; please reselect the provider and model" }
        }
        val effort = if (preserveReasoningChoice) {
            runtimeConfig.reasoningCapabilities?.normalize(entry.effectiveReasoningEffort)
                ?: runtimeConfig.effectiveReasoningEffort
        } else runtimeConfig.effectiveReasoningEffort
        val config = runtimeConfig.copy(
            terminalTools = entry.terminalTools,
            browserTools = entry.browserTools,
            deviceDirectTools = entry.deviceDirectTools,
            deviceSensitiveReadTools = entry.deviceSensitiveReadTools,
            deviceSensitiveActionTools = entry.deviceSensitiveActionTools,
            subagentTools = entry.subagentTools,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            autoCompactionEnabled = runtimeConfig.autoCompactionEnabled && entry.autoCompactionEnabled,
            supportsSearchSuggestions = entry.supportsSearchSuggestions,
        )
        val handoff = request.handoff ?: return request.copy(config = config)
        val archivePayload = AgentExternalArchivePayload.from(handoff.payload)
        return request.copy(
            config = config,
            handoff = archivePayload?.let { payload ->
                handoff.copy(
                    payload = payload.copy(
                        thinkingEnabled = config.effectiveReasoningEffort.enablesReasoning,
                        reasoningEffort = config.effectiveReasoningEffort,
                    ).toJson(),
                )
            } ?: handoff,
        )
    }
}
