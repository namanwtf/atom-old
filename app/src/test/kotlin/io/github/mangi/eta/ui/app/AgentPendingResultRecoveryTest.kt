package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPendingResultRecoveryTest {
    @Test
    fun recoveryDoesNotReplaceFailedAttemptOrRetryNotice() {
        val partial = AgentMessageUi(id = "assistant-retry-run-1-0", content = "half-finished answer")
        val notice = SystemNoticeMessageUi(
            id = "assistant-retry-run-retry-1",
            code = SystemNoticeCode.ModelRetry,
            detail = "retrying",
        )
        val state = AgentChatUiState(
            messages = listOf(partial, notice), input = "", isStreaming = true, thinkingEnabled = false,
        )
        for (ok in listOf(true, false)) {
            val recovered = AgentPendingResultRecovery.apply(
                state = state,
                runId = "retry-run",
                result = AgentRuntimeWire.RunResult(
                    runId = "retry-run", ok = ok, content = if (ok) "final answer" else "",
                    error = if (ok) null else "retries exhausted",
                ),
                supplements = emptyList(),
            )
            assertEquals(listOf(partial, notice), recovered.state.messages.take(2))
            assertEquals("assistant-retry-run-2-result", recovered.state.messages.last().id)
            assertEquals(3, recovered.state.messages.size)
        }
    }

    @Test
    fun recoveryFinalizesLatestRoundAndAppendsTranscriptExactlyOnce() {
        val transcript = listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = "final result")
        )
        val state = AgentChatUiState(
            messages = listOf(
                AgentMessageUi(
                    id = "assistant-run-1-1",
                    content = "intermediate result",
                    isStreaming = false,
                    renderMarkdown = false,
                ),
                AgentMessageUi(
                    id = "assistant-run-1-2",
                    content = "partial",
                    isStreaming = true,
                    renderMarkdown = false,
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        val supplement = AgentUiHandoffPayload.Supplement(
            index = 1,
            text = "supplementary condition",
            createdAt = 1L,
        )
        val result = AgentRuntimeWire.RunResult(
            runId = "run-1",
            ok = true,
            content = "final result",
            transcript = transcript,
        )

        val recovered = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-1",
            result = result,
            supplements = listOf(supplement),
        )

        assertFalse(recovered.alreadyApplied)
        assertEquals(
            listOf(
                "assistant-run-1-1",
                "user-run-1-supplement-1",
                "assistant-run-1-2",
            ),
            recovered.state.messages.map { it.id },
        )
        val latest = recovered.state.messages.last() as AgentMessageUi
        assertEquals("final result", latest.content)
        assertFalse(latest.isStreaming)
        assertTrue(latest.renderMarkdown)
        assertEquals(transcript, recovered.state.history)
        assertFalse(recovered.state.isStreaming)

        val replay = AgentPendingResultRecovery.apply(
            state = recovered.state,
            runId = "run-1",
            result = result,
            supplements = listOf(supplement),
        )
        assertTrue(replay.alreadyApplied)
        assertEquals(recovered.state, replay.state)
    }

    @Test
    fun recoveryCreatesAssistantWhenStreamingPlaceholderWasNeverPersisted() {
        val state = AgentChatUiState(
            messages = emptyList(),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )

        val recovered = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-2",
            result = AgentRuntimeWire.RunResult(
                runId = "run-2",
                ok = false,
                content = "",
                error = "failure reason",
            ),
            supplements = emptyList(),
        )

        assertEquals("assistant-run-2-1", recovered.state.messages.single().id)
        val message = recovered.state.messages.single() as SystemNoticeMessageUi
        assertEquals(SystemNoticeCode.RuntimeFailed, message.code)
        assertEquals("failure reason", message.detail)
    }

    @Test
    fun recoveryDoesNotMergeEarlierTextBlockIntoSameRoundFinalBlock() {
        val state = AgentChatUiState(
            messages = listOf(
                AgentMessageUi(
                    id = "assistant-run-blocks-1-1",
                    content = "let me search first.",
                    isStreaming = false,
                    renderMarkdown = true,
                ),
                AgentMessageUi(
                    id = "assistant-run-blocks-1-3",
                    content = "this is the final answer.",
                    isStreaming = false,
                    renderMarkdown = true,
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )

        val recovered = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-blocks",
            result = AgentRuntimeWire.RunResult(
                runId = "run-blocks",
                ok = true,
                content = "let me search first. This is the final answer.",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "let me search first. This is the final answer.",
                    ),
                ),
            ),
            supplements = emptyList(),
        )

        assertEquals(
            listOf("let me search first.", "this is the final answer."),
            recovered.state.messages.filterIsInstance<AgentMessageUi>().map { it.content },
        )
    }

    @Test
    fun recoveryUsesSemanticNoticeForSuccessfulRunWithoutText() {
        val recovered = AgentPendingResultRecovery.apply(
            state = AgentChatUiState(
                messages = emptyList(),
                input = "",
                isStreaming = true,
                thinkingEnabled = false,
            ),
            runId = "run-empty",
            result = AgentRuntimeWire.RunResult(
                runId = "run-empty",
                ok = true,
                content = "",
            ),
            supplements = emptyList(),
        )

        val message = recovered.state.messages.single() as SystemNoticeMessageUi
        assertEquals(SystemNoticeCode.EmptyResult, message.code)
        assertEquals(null, message.detail)
    }

    @Test
    fun completedOutboxResultKeepsToolTraceAndRemovesInterruptedNotice() {
        val tool = ToolActivityMessageUi(
            id = "run-tool-tool-1-call-1",
            toolName = "run_command",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "execute command · Android · root",
            command = "uptime",
            resultSummary = "done",
        )
        val recovered = AgentPendingResultRecovery.apply(
            state = AgentChatUiState(
                messages = listOf(
                    tool,
                    SystemNoticeMessageUi(
                        id = "interrupted-run-tool",
                        code = SystemNoticeCode.Interrupted,
                    ),
                ),
                input = "",
                isStreaming = false,
                thinkingEnabled = false,
            ),
            runId = "run-tool",
            result = AgentRuntimeWire.RunResult(
                runId = "run-tool",
                ok = true,
                content = "final result",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(role = "assistant", content = "final result")
                ),
            ),
            supplements = emptyList(),
        )

        assertFalse(recovered.alreadyApplied)
        assertEquals(tool, recovered.state.messages.first())
        assertTrue(recovered.state.messages.none { it.id == "interrupted-run-tool" })
        assertEquals("final result", (recovered.state.messages.last() as AgentMessageUi).content)
    }

    @Test
    fun appliedMarkerPreventsOldOutboxReplayAfterLaterTurns() {
        val state = AgentChatUiState(
            messages = listOf(
                AgentMessageUi(id = "assistant-run-1-1", content = "first round"),
                AgentMessageUi(id = "assistant-run-2-1", content = "second round"),
            ),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "assistant", content = "first round"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "second round"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
            appliedRuntimeRunIds = listOf("run-1", "run-2"),
        )

        val replay = AgentPendingResultRecovery.apply(
            state = state,
            runId = "run-1",
            result = AgentRuntimeWire.RunResult(
                runId = "run-1",
                ok = true,
                content = "first round",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(role = "assistant", content = "first round")
                ),
            ),
            supplements = emptyList(),
        )

        assertTrue(replay.alreadyApplied)
        assertEquals(state, replay.state)
    }

    @Test
    fun continuationPromptIsAddedToUiAndDurableHistoryOnce() {
        val prompt = AgentUiHandoffPayload.Supplement(
            index = 1,
            text = "continue checking",
            createdAt = 10L,
        )
        val recovered = AgentPendingResultRecovery.apply(
            state = AgentChatUiState(
                messages = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = false,
            ),
            runId = "run-next",
            result = AgentRuntimeWire.RunResult(
                runId = "run-next",
                ok = true,
                content = "check complete",
                transcript = listOf(
                    AgentModelClient.ConversationMessage(role = "assistant", content = "check complete")
                ),
            ),
            promptSupplement = prompt,
            supplements = emptyList(),
        )

        assertEquals(
            listOf("user-run-next-supplement-1", "assistant-run-next-1"),
            recovered.state.messages.map { it.id },
        )
        assertEquals(listOf("user", "assistant"), recovered.state.history.map { it.role })
        assertEquals("continue checking", recovered.state.history.first().content)
    }
}
