package io.github.namanwtf.atom.ui.model

import io.github.namanwtf.atom.agent.model.AgentFileReference
import io.github.namanwtf.atom.agent.model.AgentFileReferenceKind
import io.github.namanwtf.atom.agent.model.AgentFileReferencePromptCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSearchTest {

    private val noticeText: (SystemNoticeCode) -> String = { code ->
        when (code) {
            SystemNoticeCode.Stopped -> "Stopped"
            SystemNoticeCode.EmptyResult -> "Empty result"
            SystemNoticeCode.ContextCompaction -> "context compaction"
            SystemNoticeCode.ModelRetry -> "Retrying"
            SystemNoticeCode.RuntimeFailed -> "Runtime failed"
            SystemNoticeCode.Interrupted -> "Interrupted"
        }
    }

    private fun stateWith(vararg messages: AgentChatMessageUi) = AgentChatHomeUiState(
        messages = messages.toList(),
        input = "",
        isStreaming = false,
        thinkingEnabled = false,
    )

    @Test
    fun matchesUserAndAssistantTextCaseInsensitively() {
        val state = stateWith(
            UserMessageUi(id = "u", content = "what's the weather like today"),
            AgentMessageUi(id = "a", content = "DeepSeek V4 is very strong"),
        )

        assertTrue(state.contentMatches("weather", noticeText))
        assertTrue(state.contentMatches("deepseek", noticeText))
        assertFalse(state.contentMatches("does not exist", noticeText))
    }

    @Test
    fun matchesFileReferenceNameAndPathButNotEncodedHeaders() {
        val content = AgentFileReferencePromptCodec.format(
            request = "take a look at this file",
            references = listOf(
                AgentFileReference("bill.xlsx", "/sdcard/Documents/bill.xlsx", AgentFileReferenceKind.File),
            ),
        )
        val state = stateWith(UserMessageUi(id = "u", content = content))

        assertTrue(state.contentMatches("bill", noticeText))
        assertTrue(state.contentMatches("/sdcard", noticeText))
        assertFalse(state.contentMatches("Files mentioned", noticeText))
        assertFalse(state.contentMatches("My request", noticeText))
    }

    @Test
    fun matchesThinkingContent() {
        val state = stateWith(
            ThinkingMessageUi(id = "t", content = "observe the screen first then tap", isStreaming = false),
        )

        assertTrue(state.contentMatches("observe", noticeText))
        assertFalse(state.contentMatches("terminal", noticeText))
    }

    @Test
    fun matchesToolActivityFields() {
        val state = stateWith(
            ToolActivityMessageUi(
                id = "tool",
                toolName = "run_terminal_command",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "list directory",
                command = "ls -la /tmp",
                resultSummary = "total 5",
            ),
        )

        assertTrue(state.contentMatches("terminal", noticeText))
        assertTrue(state.contentMatches("list directory", noticeText))
        assertTrue(state.contentMatches("/tmp", noticeText))
        assertTrue(state.contentMatches("total", noticeText))
        assertFalse(state.contentMatches("does not exist", noticeText))
    }

    @Test
    fun matchesToolSummaryAndNotice() {
        val withSummary = stateWith(
            ToolSummaryMessageUi(id = "s", tools = listOf("observe_screen", "tap_element")),
        )
        val withNotice = stateWith(
            SystemNoticeMessageUi(id = "n", code = SystemNoticeCode.Stopped, detail = "canceled by the user"),
        )

        assertTrue(withSummary.contentMatches("observe", noticeText))
        assertTrue(withNotice.contentMatches("stopped", noticeText))
        assertTrue(withNotice.contentMatches("actively canceled", noticeText))
        assertFalse(withNotice.contentMatches("runtime failed", noticeText))
    }

    @Test
    fun ignoresNonContentMessages() {
        val state = stateWith(
            RunTraceMessageUi(id = "r", capabilities = emptyList()),
            SuggestionChipsMessageUi(id = "s", prompts = listOf("try this")),
        )

        assertFalse(state.contentMatches("try", noticeText))
    }

    @Test
    fun blankQueryMatchesEverything() {
        assertTrue(stateWith().contentMatches("  ", noticeText))
    }
}
