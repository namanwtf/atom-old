package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentOverlayStateTest {
    @Test
    fun internalStepsCollapseIntoThinkingAndDoNotFlickerDuringTools() {
        var state = AgentOverlayState.Initial.applyEvent(AgentEvent.RunStarted(0, 0, 10, false))
        val internalSteps = listOf(
            AgentEvent.RoundStarted(1, 2),
            AgentEvent.ProviderRequestStarted(1),
            AgentEvent.ProviderResponseStarted(1, 200),
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.TOOL_CALL, 0, name = "tap"),
            AgentEvent.AssistantReceived(1, 0, "", listOf("tap")),
        )
        internalSteps.forEach { state = state.applyEvent(it) }
        assertEquals(AgentOverlayStatus.Reasoning, state.status)

        state = state.applyEvent(AgentEvent.ToolStarted(1, "call-1", "tap", "{}"))
        assertEquals(AgentOverlayStatus.RunningTool("tap"), state.status)
        // Screenshot, retry, and compaction events arriving during tool execution don't rewrite the current text.
        listOf(
            AgentEvent.ToolImagesAttached(1, "tap", 1, 10),
            AgentEvent.ModelRetryScheduled(1, 1, 3, 1_000, "MODEL_TIMEOUT"),
            AgentEvent.ProviderRequestStarted(2),
        ).forEach { state = state.applyEvent(it) }
        assertEquals(AgentOverlayStatus.RunningTool("tap"), state.status)

        state = state.applyEvent(AgentEvent.ToolFinished(1, "call-1", "tap", "ok", 0, 0))
        assertEquals(AgentOverlayStatus.Reasoning, state.status)
    }

    @Test
    fun pausedStateSurvivesInFlightEventsUntilRunEnds() {
        val paused = AgentOverlayState(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
        listOf(
            AgentEvent.ToolFinished(1, "call-1", "tap", "ok", 0, 0),
            AgentEvent.ToolStarted(2, "call-2", "swipe", "{}"),
            AgentEvent.ProviderRequestStarted(2),
        ).forEach { assertEquals(paused, paused.applyEvent(it)) }
        assertEquals(AgentOverlayPhase.FAILED, paused.applyEvent(AgentEvent.RunFailed("stopped")).phase)
    }

    @Test
    fun thoughtKeepsLatestReasoningTailAndSwitchesToToolSummary() {
        var state = AgentOverlayState.Initial
            .applyEvent(AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.THINKING, 0))
        state = state.applyEvent(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 0, 0, "the user wants to book\n  tomorrow's"))
        state = state.applyEvent(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 0, 0, "high-speed rail ticket"))
        assertEquals("the user wants to book tomorrow's high-speed rail ticket", state.thought)

        val long = "very long reasoning".repeat(40)
        state = state.applyEvent(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.THINKING, 0, 0, long))
        assertEquals(96, state.thought.length)
        assertEquals(true, state.thought.endsWith("very long reasoning"))

        state = state.applyEvent(AgentEvent.ToolStarted(1, "call-1", "tap_element", "tapping element \"Search\""))
        assertEquals("tapping element \"Search\"", state.thought)
        state = state.applyEvent(AgentEvent.ToolFinished(1, "call-1", "tap_element", "ok", 0, 0))
        assertEquals("", state.thought)

        state = state.applyEvent(AgentEvent.AssistantBlockDelta(2, AgentEvent.AssistantBlockKind.THINKING, 0, 0, "old thought"))
        state = state.applyEvent(AgentEvent.AssistantBlockStart(2, AgentEvent.AssistantBlockKind.THINKING, 1))
        assertEquals("", state.thought)
    }

    @Test
    fun parallelToolsKeepRunningStatusUntilLastToolFinishes() {
        var state = AgentOverlayState.Initial
            .applyEvent(AgentEvent.ToolStarted(1, "call-1", "subagent", "explore · retrieve the calendar"))
            .applyEvent(AgentEvent.ToolStarted(1, "call-2", "subagent", "coder · check the log"))
        assertEquals(AgentOverlayStatus.RunningTool("subagent"), state.status)
        assertEquals("coder · check the log", state.thought)

        // The first concurrent Subagent to finish must not prematurely switch back to thinking.
        state = state.applyEvent(AgentEvent.ToolFinished(1, "call-2", "subagent", "done", 0, 0, success = true))
        assertEquals(AgentOverlayStatus.RunningTool("subagent"), state.status)
        assertEquals("explore · retrieve the calendar", state.thought)

        // Switch back to thinking after the last one finishes.
        state = state.applyEvent(AgentEvent.ToolFinished(1, "call-1", "subagent", "done", 0, 0, success = true))
        assertEquals(AgentOverlayStatus.Reasoning, state.status)
        assertEquals("", state.thought)
    }
}
