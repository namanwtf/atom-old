package io.github.namanwtf.atom.ui.app

import io.github.namanwtf.atom.agent.runtime.AgentEvent

/**
 * Merges adjacent text increments, so every tiny model chunk doesn't trigger a Compose state update.
 *
 * Only events of the same run, turn, content block, and type are merged here; block boundaries are still flushed immediately by the caller,
 * so the ordering semantics of Runtime events are preserved.
 */
internal class AgentRunEventCoalescer {
    private val pendingByRun = mutableMapOf<String, PendingDelta>()

    fun append(
        runId: String,
        event: AgentEvent.AssistantBlockDelta,
    ): AgentEvent.AssistantBlockDelta? {
        val pending = pendingByRun[runId]
        if (pending?.matches(event) == true) {
            pending.delta.append(event.delta)
            pending.deltaChars += event.deltaChars
            return null
        }

        val ready = pending?.toEvent()
        pendingByRun[runId] = PendingDelta(event)
        return ready
    }

    fun flush(runId: String): AgentEvent.AssistantBlockDelta? =
        pendingByRun.remove(runId)?.toEvent()

    private class PendingDelta(event: AgentEvent.AssistantBlockDelta) {
        val round = event.round
        val kind = event.kind
        val index = event.index
        var deltaChars = event.deltaChars
        val delta = StringBuilder(event.delta)

        fun matches(event: AgentEvent.AssistantBlockDelta): Boolean =
            round == event.round && kind == event.kind && index == event.index

        fun toEvent(): AgentEvent.AssistantBlockDelta =
            AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind,
                index = index,
                deltaChars = deltaChars,
                delta = delta.toString(),
            )
    }
}
