package io.github.namanwtf.atom.ui.app

import io.github.namanwtf.atom.agent.model.AgentModelClient
import io.github.namanwtf.atom.ui.model.AgentChatUiState
import io.github.namanwtf.atom.ui.model.AgentMessageUi
import io.github.namanwtf.atom.ui.model.ThinkingMessageUi
import io.github.namanwtf.atom.ui.model.ToolActivityMessageUi
import io.github.namanwtf.atom.ui.model.ToolActivityStatusUi
import io.github.namanwtf.atom.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRevisionReducerTest {
    @Test
    fun editingUserTurnCoveredBySummaryDropsStaleSummary() {
        val summary = AgentModelClient.ConversationMessage("assistant", "old operation result", contextSummary = true,
            compactedUserTurns = 1, summaryThroughUserTurn = 2)
        val state = AgentChatUiState(
            messages = listOf(UserMessageUi("old", "old request"), UserMessageUi("latest", "latest request")),
            history = listOf(summary, AgentModelClient.ConversationMessage("user", "latest request")),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        val boundary = AgentConversationRevisionReducer.boundary(state, "latest")!!
        assertTrue(boundary.contextWasCompacted)
        assertTrue(boundary.historyPrefix.isEmpty())
        val next = state.copy(messages = state.messages + UserMessageUi("next", "follow-up request"),
            history = state.history + AgentModelClient.ConversationMessage("user", "follow-up request"))
        assertTrue(AgentConversationRevisionReducer.boundary(next, "next")!!.historyPrefix.contains(summary))
    }

    @Test
    fun editingTurnAfterCompactionKeepsActiveSummaryHistoryAndTrimsJournal() {
        val summary = AgentModelClient.ConversationMessage(
            role = "assistant",
            content = "first two rounds summary",
            contextSummary = true,
            compactedUserTurns = 2,
            summaryThroughUserTurn = 2,
        )
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("user-1", "first question"),
                AgentMessageUi("assistant-1", "first answer"),
                UserMessageUi("user-2", "second question"),
                AgentMessageUi("assistant-2", "second answer"),
                UserMessageUi("user-3", "third question"),
                AgentMessageUi("assistant-3", "third answer"),
            ),
            history = listOf(
                summary,
                AgentModelClient.ConversationMessage("user", "third question"),
                AgentModelClient.ConversationMessage("assistant", "third answer"),
            ),
            journal = listOf(
                AgentModelClient.ConversationMessage("user", "first question"),
                AgentModelClient.ConversationMessage("assistant", "first answer"),
                AgentModelClient.ConversationMessage("user", "second question"),
                AgentModelClient.ConversationMessage("assistant", "second answer"),
                AgentModelClient.ConversationMessage("user", "third question"),
                AgentModelClient.ConversationMessage("assistant", "third answer"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )

        val boundary = AgentConversationRevisionReducer.boundary(state, "user-3")!!

        assertFalse(boundary.contextWasCompacted)
        assertEquals(listOf(summary), boundary.historyPrefix)
        assertEquals(
            listOf("first question", "first answer", "second question", "second answer"),
            boundary.journalPrefix.map { it.content },
        )
    }

    @Test
    fun editingFirstTurnClearsBothHistoryAndJournalPrefixes() {
        val state = conversationState().copy(journal = conversationState().history)

        val boundary = AgentConversationRevisionReducer.boundary(state, "user-1")!!

        assertFalse(boundary.contextWasCompacted)
        assertTrue(boundary.historyPrefix.isEmpty())
        assertTrue(boundary.journalPrefix.isEmpty())
    }

    @Test
    fun boundaryMapsAssistantToItsUserTurnAndKeepsToolTranscriptPrefix() {
        val state = conversationState()

        val boundary = AgentConversationRevisionReducer.boundary(state, "assistant-2")!!

        assertEquals("user-2", boundary.userMessage.id)
        assertEquals(4, boundary.userMessageIndex)
        assertEquals(1, boundary.laterTurnCount)
        assertFalse(boundary.contextWasCompacted)
        assertEquals(
            listOf("user", "assistant", "tool", "assistant"),
            boundary.historyPrefix.map { it.role },
        )
    }

    @Test
    fun deleteFromMiddleTurnTruncatesMessagesAndHistoryTogether() {
        val state = conversationState().copy(appliedRuntimeRunIds = listOf("run-1", "run-2"))

        val revised = AgentConversationRevisionReducer.deleteFromTurn(state, "assistant-2")!!

        assertEquals(
            listOf("user-1", "thinking-1", "tool-1", "assistant-1"),
            revised.messages.map { it.id },
        )
        assertEquals(listOf("user", "assistant", "tool", "assistant"), revised.history.map { it.role })
        assertEquals(listOf("run-1", "run-2"), revised.appliedRuntimeRunIds)
    }

    @Test
    fun compactedCheckpointAlignsRetainedTurnsFromTheTail() {
        val full = conversationState()
        val compacted = full.copy(
            history = listOf(
                AgentModelClient.ConversationMessage(role = "system", content = "compacted"),
                AgentModelClient.ConversationMessage(role = "user", content = "second question"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "second answer"),
                AgentModelClient.ConversationMessage(role = "user", content = "third question"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "third answer"),
            )
        )

        val missing = AgentConversationRevisionReducer.boundary(compacted, "user-1")!!
        val retained = AgentConversationRevisionReducer.boundary(compacted, "user-2")!!

        assertTrue(missing.contextWasCompacted)
        assertTrue(missing.historyPrefix.isEmpty())
        assertFalse(retained.contextWasCompacted)
        assertEquals(listOf("system"), retained.historyPrefix.map { it.role })
    }

    @Test
    fun visibleMessagesStopAtEditedUserWithoutMutatingTheSource() {
        val messages = conversationState().messages

        val visible = AgentConversationRevisionReducer.visibleMessagesForEdit(messages, "user-2")

        assertEquals(listOf("user-1", "thinking-1", "tool-1", "assistant-1", "user-2"), visible.map { it.id })
        assertEquals(8, messages.size)
    }

    @Test
    fun invalidMessageDoesNotChangeConversation() {
        val state = conversationState()

        assertNull(AgentConversationRevisionReducer.boundary(state, "missing"))
        assertNull(AgentConversationRevisionReducer.deleteFromTurn(state, "missing"))
        assertEquals(
            state.messages,
            AgentConversationRevisionReducer.visibleMessagesForEdit(state.messages, "missing"),
        )
    }

    private fun conversationState(): AgentChatUiState = AgentChatUiState(
        messages = listOf(
            UserMessageUi(id = "user-1", content = "first question"),
            ThinkingMessageUi(id = "thinking-1", content = "thinking", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-1",
                toolName = "test",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            AgentMessageUi(id = "assistant-1", content = "first answer"),
            UserMessageUi(id = "user-2", content = "second question", images = listOf("data:image/png;base64,AA==")),
            AgentMessageUi(id = "assistant-2", content = "second answer"),
            UserMessageUi(id = "user-3", content = "third question"),
            AgentMessageUi(id = "assistant-3", content = "third answer"),
        ),
        history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "first question"),
            AgentModelClient.ConversationMessage(role = "assistant", toolCallsJson = "[]"),
            AgentModelClient.ConversationMessage(role = "tool", content = "result"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "first answer"),
            AgentModelClient.ConversationMessage(role = "user", content = "second question"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "second answer"),
            AgentModelClient.ConversationMessage(role = "user", content = "third question"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "third answer"),
        ),
        input = "draft",
        isStreaming = false,
        thinkingEnabled = false,
    )
}
