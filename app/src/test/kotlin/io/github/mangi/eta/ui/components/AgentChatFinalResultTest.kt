package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentChatFinalResultTest {

    @Test
    fun onlyLastAgentMessageOfEachTurnIsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "look up some material"),
            AgentMessageUi(id = "agent-1", content = "let me search for you."),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-2", content = "as you can see from the search results..."),
            toolActivity("tool-2"),
            AgentMessageUi(id = "agent-3", content = "final answer"),
        )

        assertEquals(setOf("agent-3"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun multipleTurnsEachHaveTheirOwnFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "first question"),
            AgentMessageUi(id = "agent-1", content = "first answer"),
            UserMessageUi(id = "user-2", content = "second question"),
            AgentMessageUi(id = "agent-2", content = "intermediate step"),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-3", content = "second answer"),
        )

        assertEquals(setOf("agent-1", "agent-3"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun turnInterruptedAfterToolKeepsPreviousAgentMessageAsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "task"),
            AgentMessageUi(id = "agent-1", content = "let me try first."),
            toolActivity("tool-1"),
        )

        assertEquals(setOf("agent-1"), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun turnWithoutAgentMessageProducesNoFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "task"),
            toolActivity("tool-1"),
        )

        assertEquals(emptySet<String>(), resolveFinalResultMessageIds(messages))
    }

    @Test
    fun streamingTurnDoesNotMarkIntermediateMessageAsFinalResult() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "task"),
            AgentMessageUi(id = "agent-1", content = "let me search for you."),
            toolActivity("tool-1"),
        )

        assertEquals(
            emptySet<String>(),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
    }

    @Test
    fun streamingKeepsFinalResultOfCompletedEarlierTurns() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "first question"),
            AgentMessageUi(id = "agent-1", content = "first answer"),
            UserMessageUi(id = "user-2", content = "second question"),
            AgentMessageUi(id = "agent-2", content = "intermediate step"),
            toolActivity("tool-1"),
        )

        assertEquals(
            setOf("agent-1"),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
    }

    @Test
    fun streamingEndRestoresFinalResultOfLastTurn() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "task"),
            AgentMessageUi(id = "agent-1", content = "intermediate step"),
            toolActivity("tool-1"),
            AgentMessageUi(id = "agent-2", content = "final answer"),
        )

        assertEquals(
            emptySet<String>(),
            resolveFinalResultMessageIds(messages, isStreaming = true),
        )
        assertEquals(
            setOf("agent-2"),
            resolveFinalResultMessageIds(messages, isStreaming = false),
        )
    }

    private fun toolActivity(id: String): AgentChatMessageUi = ToolActivityMessageUi(
        id = id,
        toolName = "browser_use",
        status = ToolActivityStatusUi.Success,
        argumentsSummary = "query",
    )
}
