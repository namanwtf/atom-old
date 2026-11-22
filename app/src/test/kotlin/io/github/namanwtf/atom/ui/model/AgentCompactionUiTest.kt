package io.github.namanwtf.atom.ui.model

import io.github.namanwtf.atom.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test

class AgentCompactionUiTest {
    @Test
    fun manualActionRequiresIdleCompletedHistory() {
        val empty = AgentChatUiState(emptyList(), input = "draft", isStreaming = false, thinkingEnabled = false)
        assertFalse(empty.canCompactContext)
        val ready = empty.copy(history = listOf(AgentModelClient.ConversationMessage("assistant", "completed")))
        assertTrue(ready.canCompactContext)
        assertFalse(ready.copy(isStreaming = true).canCompactContext)
        assertFalse(empty.copy(history = listOf(AgentModelClient.ConversationMessage("assistant", "summary only", contextSummary = true))).canCompactContext)
    }

    @Test
    fun compactionInvalidatesPreviousUsageUntilNextModelResponse() {
        val response = AgentMessageUi("answer", "answer", usage = TokenUsageUi(contextTokens = 30_000))
        val compaction = SystemNoticeMessageUi("compact", SystemNoticeCode.ContextCompaction,
            "compacted", contextTokens = 3_000)
        val usage = latestContextUsage(listOf(response, compaction), null)
        assertNull(usage.contextTokens)
        val next = latestContextUsage(listOf(response, compaction, response.copy(id = "next", usage = TokenUsageUi(contextTokens = 4_000))), null)
        assertEquals(4_000, next.contextTokens)
    }
}
