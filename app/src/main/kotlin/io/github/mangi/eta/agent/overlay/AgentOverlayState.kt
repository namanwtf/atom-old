package io.github.mangi.eta.agent.overlay

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.GuiControlStatus

/** The phase the Agent overlay is in. */
internal enum class AgentOverlayPhase { RUNNING, PAUSED, FINISHED, FAILED }

/**
 * Render state of the Agent overlay, accumulated from the [AgentEvent] stream and consumed directly by [AgentOverlayBubble].
 */
@Immutable
internal data class AgentOverlayState(
    val phase: AgentOverlayPhase = AgentOverlayPhase.RUNNING,
    val round: Int = 0,
    val status: AgentOverlayStatus = AgentOverlayStatus.Reasoning,
    val detailText: String = "",
    /**
     * Capsule second line: while thinking it is the model's latest visible reasoning; while running a tool it is the argument summary (e.g. "taps "Search"").
     * Only a short trailing segment is kept so the user can glance at what the Agent is thinking; the full reasoning stays in the conversation.
     */
    val thought: String = "",
    /** Currently in-flight tool calls; ensures that with multiple concurrent Subagents the state does not switch back to thinking early just because the first one finished. */
    val activeTools: List<ActiveTool> = emptyList(),
) {
    @Immutable
    data class ActiveTool(
        val callId: String,
        val name: String,
        val argsPreview: String = "",
    )

    companion object {
        val Initial = AgentOverlayState(status = AgentOverlayStatus.Reasoning)
    }
}

/**
 * Folds a single [AgentEvent] into the current render state.
 *
 * The capsule and notification only surface the few phases the user cares about: thinking, operating a tool, and composing the answer.
 * Internal steps such as requesting the model, model responded, generating tool arguments, rounds, and reading screenshots are not exposed individually:
 * they appear every round and last less than a second, so frequent switching would only distract the user.
 * Between one tool finishing and the next call, the state also does not switch to "done"; it stays in the operating phase to avoid the text jumping back and forth.
 */
internal fun AgentOverlayState.applyEvent(event: AgentEvent): AgentOverlayState {
    // Pause takes effect at the next checkpoint; events like an already in-flight tool finishing must not switch the text back to the running state.
    if (phase == AgentOverlayPhase.PAUSED && event !is AgentEvent.RunFinished &&
        event !is AgentEvent.RunFailed && event !is AgentEvent.GuiControlChanged) {
        return this
    }
    return projectEvent(event)
}

private fun AgentOverlayState.projectEvent(event: AgentEvent): AgentOverlayState = when (event) {
    is AgentEvent.GuiControlChanged -> when (event.status) {
        GuiControlStatus.UserPaused -> copy(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
        GuiControlStatus.Acquired -> copy(phase = AgentOverlayPhase.RUNNING, status = AgentOverlayStatus.Continuing)
        GuiControlStatus.Waiting, GuiControlStatus.Released -> this
    }
    is AgentEvent.RunStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.Reasoning,
        detailText = "",
        activeTools = emptyList(),
    )
    is AgentEvent.RoundStarted -> copy(round = event.round).thinkingUnlessWorking()
    is AgentEvent.ProviderRequestStarted -> copy(round = event.round).thinkingUnlessWorking()
    is AgentEvent.ModelRetryScheduled -> copy(round = event.round, detailText = event.displayMessage).thinkingUnlessWorking()
    is AgentEvent.ContextCompaction -> copy(detailText = event.displayMessage).thinkingUnlessWorking()

    is AgentEvent.AssistantBlockDelta -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT -> appendStreamingText(event)
        AgentEvent.AssistantBlockKind.THINKING -> copy(thought = appendThought(thought, event.delta))
        AgentEvent.AssistantBlockKind.TOOL_CALL -> this
    }

    // Each new round of thinking starts blank, to avoid treating the previous round's trailing sentence as the current thought.
    is AgentEvent.AssistantBlockStart -> if (event.kind == AgentEvent.AssistantBlockKind.THINKING) copy(thought = "") else this

    is AgentEvent.ToolStarted -> {
        val preview = event.argsPreview.trim()
        val updatedTools = activeTools.filterNot { event.toolCallId.isNotBlank() && it.callId == event.toolCallId } +
            AgentOverlayState.ActiveTool(
                callId = event.toolCallId,
                name = event.name,
                argsPreview = preview,
            )
        running(AgentOverlayStatus.RunningTool(event.name), event.round)
            .copy(thought = preview, activeTools = updatedTools)
    }
    is AgentEvent.HostedToolStarted -> running(AgentOverlayStatus.HostedToolRunning(event.name), event.round)

    // When multiple tools run concurrently (e.g. parallel Subagents), switch back to thinking only after all in-flight tools have finished.
    is AgentEvent.ToolFinished -> {
        val remaining = if (event.toolCallId.isNotBlank() && activeTools.any { it.callId == event.toolCallId }) {
            activeTools.filterNot { it.callId == event.toolCallId }
        } else {
            emptyList()
        }
        val nextTool = remaining.lastOrNull()
        if (nextTool != null) {
            running(AgentOverlayStatus.RunningTool(nextTool.name), event.round)
                .copy(thought = nextTool.argsPreview, activeTools = remaining)
        } else {
            running(AgentOverlayStatus.Reasoning, event.round)
                .copy(thought = "", activeTools = emptyList())
        }
    }
    is AgentEvent.HostedToolFinished -> running(AgentOverlayStatus.Reasoning, event.round).copy(thought = "")

    is AgentEvent.UserSupplementReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        status = AgentOverlayStatus.SupplementReceived,
        detailText = "",
    )

    is AgentEvent.RunFinished -> copy(
        phase = AgentOverlayPhase.FINISHED,
        round = event.round,
        status = AgentOverlayStatus.ResultReady,
        activeTools = emptyList(),
    )

    is AgentEvent.RunFailed -> copy(
        phase = AgentOverlayPhase.FAILED,
        status = AgentOverlayStatus.RunFailed,
        detailText = event.reason,
        activeTools = emptyList(),
    )

    is AgentEvent.SearchSuggestions,
    is AgentEvent.AssistantBlockEnd,
    is AgentEvent.AssistantReceived,
    is AgentEvent.ProviderResponseStarted,
    is AgentEvent.UsageReceived,
    is AgentEvent.ToolImagesAttached,
    is AgentEvent.SubagentProgress,
    -> this
}

private fun AgentOverlayState.running(status: AgentOverlayStatus, round: Int) =
    copy(phase = AgentOverlayPhase.RUNNING, round = round, status = status)

/** While running a tool or producing an answer, background request, retry, and compaction events do not interrupt the current text. */
private fun AgentOverlayState.thinkingUnlessWorking(): AgentOverlayState = when (status) {
    is AgentOverlayStatus.RunningTool,
    is AgentOverlayStatus.HostedToolRunning,
    AgentOverlayStatus.GeneratingAnswer,
    AgentOverlayStatus.Paused,
    AgentOverlayStatus.Stopping,
    -> this
    else -> copy(phase = AgentOverlayPhase.RUNNING, status = AgentOverlayStatus.Reasoning)
}

private const val MaxStreamingPreviewChars = 320
private const val MaxThoughtChars = 96

/** Merges whitespace and keeps only the tail; the capsule shows "what was just thought", not the whole reasoning. */
private fun appendThought(current: String, delta: String): String {
    val merged = (current + delta).replace(Regex("\\s+"), " ").trimStart()
    return if (merged.length <= MaxThoughtChars) merged else merged.takeLast(MaxThoughtChars).trimStart()
}

private fun AgentOverlayState.appendStreamingText(event: AgentEvent.AssistantBlockDelta): AgentOverlayState {
    val nextPreview = (detailText + event.delta)
        .trimStart()
        .take(MaxStreamingPreviewChars)
    return copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        status = AgentOverlayStatus.GeneratingAnswer,
        detailText = nextPreview,
    )
}
