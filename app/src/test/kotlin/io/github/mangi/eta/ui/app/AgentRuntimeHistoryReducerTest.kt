package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeHistoryReducerTest {
    @Test
    fun journalRetainsOriginalsAndContextIncludesPostSnapshotSteps() {
        val original = AgentModelClient.ConversationMessage("user", "old constraint that must not be lost")
        val first = AgentModelClient.ConversationMessage("assistant", "executed before the snapshot")
        val later = AgentModelClient.ConversationMessage("assistant", "executed after the snapshot")
        val summary = AgentModelClient.ConversationMessage("assistant", "summary", contextSummary = true)
        val state = AgentChatUiState(emptyList(), history = listOf(original), journal = listOf(original),
            input = "", isStreaming = false, thinkingEnabled = false)
        val snapshot = io.github.mangi.eta.agent.model.AgentContextSnapshot(operationId = "run", messages = listOf(summary),
            consumedUserTurns = 1, consumedTranscriptMessages = 1)
        val result = AgentRuntimeHistoryReducer.apply(state, "run", listOf(first, later), snapshot).state
        assertEquals(listOf(original, first, later), result.journal)
        assertEquals(listOf(summary, later), result.history)
        assertEquals(result, AgentRuntimeHistoryReducer.apply(result, "run", listOf(first, later), snapshot).state)
    }

    @Test
    fun editingSummarizedTurnRebuildsFromOriginalJournal() {
        val first = AgentModelClient.ConversationMessage("user", "original instruction")
        val answer = AgentModelClient.ConversationMessage("assistant", "original result")
        val second = AgentModelClient.ConversationMessage("user", "follow-up question")
        val summary = AgentModelClient.ConversationMessage("assistant", "incomplete summary", contextSummary = true, compactedUserTurns = 2)
        val state = AgentChatUiState(listOf(
            io.github.mangi.eta.ui.model.UserMessageUi("u1", "original instruction"),
            io.github.mangi.eta.ui.model.UserMessageUi("u2", "follow-up question"),
        ), history = listOf(summary), journal = listOf(first, answer, second),
            input = "", isStreaming = false, thinkingEnabled = false)
        val boundary = checkNotNull(AgentConversationRevisionReducer.boundary(state, "u2"))
        assertEquals(listOf(first, answer), boundary.historyPrefix)
        org.junit.Assert.assertFalse(boundary.contextWasCompacted)
    }

    @Test
    fun replacementKeepsLaterUserMessagesAndIsAppliedOnce() {
        val old = AgentModelClient.ConversationMessage("user", "old question")
        val later = AgentModelClient.ConversationMessage("user", "appended later")
        val summary = AgentModelClient.ConversationMessage("assistant", "summary", contextSummary = true, compactedUserTurns = 1)
        val snapshot = io.github.mangi.eta.agent.model.AgentContextSnapshot(
            operationId = "run-1", messages = listOf(summary), consumedUserTurns = 1, coveredUserTurns = 1,
        )
        val state = AgentChatUiState(emptyList(), history = listOf(old, later), input = "", isStreaming = false, thinkingEnabled = false)
        val first = AgentRuntimeHistoryReducer.apply(state, "run-1", listOf(AgentModelClient.ConversationMessage("assistant", "must not be appended twice")), snapshot)
        assertEquals(listOf(summary, later), first.state.history)
        val again = AgentRuntimeHistoryReducer.apply(first.state, "run-1", emptyList(), snapshot)
        assertTrue(again.alreadyApplied)
        assertEquals(first.state.history, again.state.history)
    }

    @Test
    fun snapshotKeepsAcceptedButUnconsumedSteeringAfterCancellation() {
        val initial = AgentChatUiState(
            messages = listOf(
                io.github.mangi.eta.ui.model.UserMessageUi("user-run-supplement-1", "consumed"),
                io.github.mangi.eta.ui.model.UserMessageUi("user-run-supplement-2", "pending consumption"),
            ), history = emptyList(), input = "", isStreaming = false, thinkingEnabled = false,
        )
        val snapshot = io.github.mangi.eta.agent.model.AgentContextSnapshot(
            operationId = "run", messages = listOf(AgentModelClient.ConversationMessage("assistant", "completed prior steps")),
            consumedSupplementCount = 1,
        )
        val result = AgentRuntimeHistoryReducer.apply(initial, "run", emptyList(), snapshot)
        assertEquals(listOf("completed prior steps", "pending consumption"), result.state.history.map { it.content })
    }

    @Test
    fun recoveryThenLiveDeliveryCommitsTranscriptOnlyOnce() {
        val initial = AgentChatUiState(
            messages = emptyList(),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        val transcript = listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = "done")
        )

        val recovered = AgentRuntimeHistoryReducer.apply(initial, "run-1", transcript)
        val live = AgentRuntimeHistoryReducer.apply(recovered.state, "run-1", transcript)

        assertTrue(live.alreadyApplied)
        assertEquals(transcript, live.state.history)
        assertEquals(listOf("run-1"), live.state.appliedRuntimeRunIds)
    }
}
