package io.github.namanwtf.atom.agent.runtime

/** Tool-argument increments are not for display; search suggestions only allow in-memory reconnect replay of an active run. */
internal fun AgentEvent.replayProjection(): AgentEvent? = when (this) {
    is AgentEvent.AssistantBlockDelta ->
        takeUnless { kind == AgentEvent.AssistantBlockKind.TOOL_CALL }
    else -> this
}

/** Web suggestions are not persisted; GUI ownership and Subagent intermediate progress are valid only in the current process; all three keep reconnect replay of an active run. */
internal fun AgentEvent.recoveryProjection(): AgentEvent? = when (this) {
    is AgentEvent.SearchSuggestions,
    is AgentEvent.GuiControlChanged,
    is AgentEvent.SubagentProgress,
    -> null
    else -> replayProjection()
}
