package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunMessageProjectorTest {
    @Test
    fun searchSuggestionsBindToExactTextBlockAndDoNotPersistIntoAnotherRun() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta("run", 1, 0, "first block", emptyList())
        messages = projector.appendTextDelta("run", 1, 3, "answer with sources", messages)
        val event = AgentEvent.SearchSuggestions(1, 3, "<div>search suggestions</div>")
        val attached = projector.applyEvent("run", event, messages)
        assertTrue((attached[0] as AgentMessageUi).searchSuggestionsHtml.isEmpty())
        assertEquals(listOf(event.html), (attached[1] as AgentMessageUi).searchSuggestionsHtml)
        assertSame(attached, projector.applyEvent("run", event, attached))
        assertSame(attached, projector.applyEvent("other", event, attached))
        assertSame(attached, projector.applyEvent("run", event.copy(index = 7), attached))
        val finalized = projector.finalizeRun("run", attached)
        assertEquals(listOf(event.html), (finalized[1] as AgentMessageUi).searchSuggestionsHtml)
        assertTrue(projector.resetForReplay("run", finalized).isEmpty())
    }

    @Test
    fun searchSuggestionsEnforcePerRoundBudgetAcrossMultipleAnswerBlocks() {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = projector.appendTextDelta("run", 1, 0, "first block", emptyList())
        messages = projector.appendTextDelta("run", 1, 1, "second block", messages)
        repeat(5) { index ->
            messages = projector.applyEvent("run", AgentEvent.SearchSuggestions(1, index % 2, "suggestion-$index"), messages)
        }
        assertEquals(5, messages.filterIsInstance<AgentMessageUi>().sumOf { it.searchSuggestionsHtml.size })
        assertSame(messages, projector.applyEvent("run", AgentEvent.SearchSuggestions(1, 0, "the sixth one"), messages))
    }

    @Test
    fun eventsWithoutVisibleChangeReturnSameListSoCallerSkipsTimestamp() {
        val projector = AgentRunMessageProjector { 1_000L }
        val messages = projector.appendTextDelta("run", 1, 0, "answer", emptyList())
        listOf(
            AgentEvent.RoundStarted(1, 2),
            AgentEvent.ProviderRequestStarted(1),
            AgentEvent.AssistantReceived(1, 2, "", emptyList()),
            AgentEvent.UsageReceived(1, AgentTokenUsage(null, null, null, null, null)),
            AgentEvent.UsageReceived(9, AgentTokenUsage(null, 10, 2, null, null)),
            AgentEvent.UserSupplementReceived(0, "supplement"),
        ).forEach { event -> assertSame(event.toString(), messages, projector.applyEvent("run", event, messages)) }
    }

    @Test
    fun usageAttachesToRoundWithoutRestartingStreaming() {
        val projector = AgentRunMessageProjector { 1_000L }
        val streaming = projector.appendTextDelta("run", 1, 0, "answer", emptyList())
        val finished = projector.finalizeTextBlock("run", 1, 0, null, streaming)
        val withUsage = projector.applyEvent("run", AgentEvent.UsageReceived(1, AgentTokenUsage(100, 80, 20, null, null)), finished)
        val message = withUsage.filterIsInstance<AgentMessageUi>().single()
        assertEquals(100, message.usage?.contextTokens)
        assertFalse(message.isStreaming)
    }

    @Test
    fun applyResultFillsSingleBlockKeepsMultiBlockAndReplacesWithNotice() {
        val projector = AgentRunMessageProjector { 1_000L }
        val single = projector.appendTextDelta("run", 1, 0, "streaming half", emptyList())
        assertEquals("complete answer", AgentRunMessageProjector.applyResult("run", single, "complete answer", null)
            .filterIsInstance<AgentMessageUi>().single().content)

        val multi = projector.appendTextDelta("run", 1, 1, "second block", projector.finalizeTextBlock("run", 1, 0, null, single))
        assertEquals(listOf("streaming half", "second block"), AgentRunMessageProjector.applyResult("run", multi, "merged full text", null)
            .filterIsInstance<AgentMessageUi>().map { it.content })

        val failed = AgentRunMessageProjector.applyResult("run", single, "", SystemNoticeCode.RuntimeFailed, "error")
        val notice = failed.single() as SystemNoticeMessageUi
        assertEquals(single.single().id, notice.id)
        assertEquals("error", notice.detail)

        val empty = AgentRunMessageProjector.applyResult("other", emptyList(), "answer", null)
        assertEquals("assistant-other-1", empty.single().id)
    }

    @Test
    fun retryKeepsFailedAttemptSeparateAndReplayClearsItsNotice() {
        val projector = AgentRunMessageProjector { 1_000L }
        val partial = projector.appendTextDelta("retry-run", 2, 0, "half", emptyList())
        val event = AgentEvent.ModelRetryScheduled(2, 1, 3, 2_000, "MODEL_TIMEOUT")
        val retrying = projector.scheduleModelRetry("retry-run", event, partial)
        assertEquals(-1, AgentRunMessageProjector.resultTargetIndex("retry-run", retrying))
        assertEquals("assistant-retry-run-3-result", AgentRunMessageProjector.resultFallbackId("retry-run", retrying))
        val repeated = projector.scheduleModelRetry("retry-run", event, retrying)
        assertEquals(retrying, repeated)
        val resumed = projector.appendTextDelta("retry-run", 3, 0, "complete answer", retrying)
        assertEquals(listOf("half", "complete answer"), resumed.filterIsInstance<AgentMessageUi>().map { it.content })
        assertFalse(resumed.filterIsInstance<AgentMessageUi>().first().isStreaming)
        assertEquals(SystemNoticeCode.ModelRetry, resumed.filterIsInstance<SystemNoticeMessageUi>().single().code)
        assertTrue(projector.resetForReplay("retry-run", resumed).isEmpty())
    }

    @Test
    fun runCompletionFinalizesUnclosedBlocksAndUnknownToolWithoutChangingOtherRuns() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-final"
        val knownTools = listOf(
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-success",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
                resultSummary = "done",
            ),
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-failed",
                toolName = "run_command",
                status = ToolActivityStatusUi.Failed,
                argumentsSummary = "{}",
                resultSummary = "execution failed",
            ),
        )
        val otherRunMessages = listOf(
            AgentMessageUi(id = "assistant-run-other-1-0", content = "still outputting", isStreaming = true),
            ThinkingMessageUi(id = "run-other-thinking-1-0", content = "still thinking", isStreaming = true),
            ToolActivityMessageUi(
                id = "run-other-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Running,
                argumentsSummary = "{}",
            ),
        )
        val unfinishedTool = ToolActivityMessageUi(
            id = "$runId-tool-1-call-unknown",
            toolName = "run_command",
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "{}",
        )
        val messages = otherRunMessages + knownTools + listOf(
            AgentMessageUi(id = "assistant-$runId", content = "", isStreaming = true),
            AgentMessageUi(id = "assistant-$runId-1-0", content = "answer\n\n", isStreaming = true),
            ThinkingMessageUi(id = "$runId-thinking-1-0", content = "thinking", isStreaming = true),
            unfinishedTool,
        )

        val finalized = projector.finalizeRun(runId, messages)

        assertEquals(otherRunMessages + knownTools, finalized.take(otherRunMessages.size + knownTools.size))
        val assistantMessages = finalized.filterIsInstance<AgentMessageUi>()
            .filter { it.id.startsWith("assistant-$runId") }
        assertTrue(assistantMessages.all { !it.isStreaming && it.renderMarkdown })
        assertEquals("answer", assistantMessages.last().content)
        val thinking = finalized.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith(runId) }
        assertFalse(thinking.isStreaming)
        assertTrue(thinking.collapsed)
        assertEquals(unfinishedTool.copy(status = ToolActivityStatusUi.Unknown), finalized.last())
    }

    @Test
    fun replayResetRemovesOnlyRebuildableTraceAndExplicitlyReplayedSupplements() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-replay"
        val user = UserMessageUi(
            id = "user-$runId",
            content = "analyze screenshot",
            images = listOf("image-preview"),
        )
        val legacySupplement = UserMessageUi(id = "user-$runId-supplement-1", content = "original supplement")
        val replayedSupplement = UserMessageUi(id = "user-$runId-supplement-2", content = "continue checking")
        val otherRunMessages = listOf(
            UserMessageUi(id = "user-other-run", content = "previous question"),
            AgentMessageUi(id = "assistant-other-run-1-0", content = "previous answer"),
            ThinkingMessageUi(id = "other-run-thinking-1-0", content = "previous thinking", isStreaming = false),
            ToolActivityMessageUi(
                id = "other-run-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            UserMessageUi(id = "user-other-run-supplement-2", content = "previous supplement"),
        )
        val messages = otherRunMessages + listOf(
            user,
            AgentMessageUi(id = "assistant-$runId", content = "", isStreaming = true),
            AgentMessageUi(id = "assistant-$runId-1-0", content = "half-finished answer", isStreaming = true),
            ThinkingMessageUi(id = "$runId-thinking-1-fallback", content = "half-finished thinking", isStreaming = true),
            ToolActivityMessageUi(
                id = "$runId-tool-1-call-1",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Unknown,
                argumentsSummary = "{}",
            ),
            legacySupplement,
            replayedSupplement,
            SystemNoticeMessageUi(id = "assistant-$runId-2", code = SystemNoticeCode.RuntimeFailed),
            SystemNoticeMessageUi(id = "interrupted-$runId", code = SystemNoticeCode.Interrupted),
        )

        val reset = projector.resetForReplay(runId, messages, replaySupplementIndexes = setOf(2))

        assertEquals(otherRunMessages + listOf(user, legacySupplement), reset)
        assertEquals(reset, projector.resetForReplay(runId, reset, replaySupplementIndexes = setOf(2)))
    }

    @Test
    fun repeatedReplayRebuildsTextReasoningAndToolsWithoutAccumulatingContent() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-repeat"
        val user = UserMessageUi(id = "user-$runId", content = "look at the screen")
        val toolStart = AgentEvent.ToolStarted(
            round = 1,
            toolCallId = "call-1",
            name = "observe_screen",
            argsPreview = "{}",
        )
        val toolEnd = AgentEvent.ToolFinished(
            round = 1,
            toolCallId = "call-1",
            name = "observe_screen",
            resultSummary = "done",
            imageCount = 0,
            imageBytes = 0,
            success = true,
        )
        var messages: List<AgentChatMessageUi> = listOf(user)

        repeat(3) {
            messages = projector.resetForReplay(runId, messages)
            messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "check first", messages)
            messages = projector.finalizeThinking(runId, messages)
            messages = projector.startTool(runId, toolStart, messages)
            messages = projector.finishTool(runId, toolEnd, messages)
            messages = projector.appendTextDelta(runId, round = 2, index = 0, delta = "check", messages)
            messages = projector.appendTextDelta(runId, round = 2, index = 0, delta = "done", messages)
            messages = projector.finalizeText(runId, messages)

            assertEquals(4, messages.size)
            assertEquals(user, messages.first())
            assertEquals("check first", messages.filterIsInstance<ThinkingMessageUi>().single().content)
            assertEquals("checkdone", messages.filterIsInstance<AgentMessageUi>().single().content)
            assertEquals(ToolActivityStatusUi.Success, messages.filterIsInstance<ToolActivityMessageUi>().single().status)
        }
    }

    @Test
    fun replayResetClearsOnlyThatRunsThinkingClockAndKeepsUnreplayedUserInputs() {
        var now = 1_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val supplement = UserMessageUi(id = "user-run-reset-supplement-1", content = "keep this supplement")
        var messages: List<AgentChatMessageUi> = listOf(supplement)
        messages = projector.appendReasoningDelta("run-reset", round = 1, index = 0, delta = "previous thinking", messages)
        messages = projector.appendReasoningDelta("run-other", round = 1, index = 0, delta = "other thinking", messages)
        now = 9_000L

        messages = projector.resetForReplay("run-reset", messages)
        messages = projector.appendReasoningDelta("run-reset", round = 1, index = 0, delta = "restored thinking", messages)
        now = 11_000L
        messages = projector.finalizeThinking("run-reset", messages)
        messages = projector.finalizeThinking("run-other", messages)

        assertTrue(messages.contains(supplement))
        assertEquals(2, messages.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith("run-reset-") }.elapsedSeconds)
        assertEquals(10, messages.filterIsInstance<ThinkingMessageUi>().single { it.id.startsWith("run-other-") }.elapsedSeconds)
    }

    @Test
    fun projectsReasoningAndToolsByRoundAndToolCallId() {
        var now = 1_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val runId = "run-1"
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "look at the screen"))

        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "observe first", messages)
        now = 4_000L
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_observe_1",
                name = "observe_screen",
                argsPreview = "{}",
            ),
            projector.finalizeThinkingRound(runId, round = 1, messages)
        )
        messages = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_observe_1",
                name = "observe_screen",
                resultSummary = "ok=true, chars=10",
                imageCount = 1,
                imageBytes = 200,
            ),
            messages
        )

        now = 5_000L
        messages = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "confirm again", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_observe_2",
                name = "run_command",
                argsPreview = "execute command · Android · root",
                command = "pm list packages | head",
            ),
            projector.finalizeThinkingRound(runId, round = 2, messages)
        )

        assertEquals(
            listOf(
                "user-$runId",
                "$runId-thinking-1-0",
                "$runId-tool-1-call_observe_1",
                "$runId-thinking-2-0",
                "$runId-tool-2-call_observe_2",
            ),
            messages.map { it.id }
        )

        val firstThinking = messages[1] as ThinkingMessageUi
        assertFalse(firstThinking.isStreaming)
        assertEquals(3, firstThinking.elapsedSeconds)

        val firstTool = messages[2] as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Success, firstTool.status)
        assertEquals(1, firstTool.imageCount)

        val secondTool = messages[4] as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Running, secondTool.status)
        assertEquals("execute command · Android · root", secondTool.argumentsSummary)
        assertEquals("pm list packages | head", secondTool.command)
    }

    @Test
    fun keepsAssistantTextSeparatedByRound() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-text"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "analyze"),
            AgentMessageUi(id = "assistant-$runId-1", content = "first round", isStreaming = false),
        )

        messages = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "continue reasoning", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "call_2",
                name = "observe_screen",
                argsPreview = "{}",
            ),
            projector.finalizeThinkingRound(runId, round = 2, messages)
        )
        messages = projector.appendTextDelta(runId, round = 2, index = 1, delta = "second round", messages)
        messages = projector.appendTextDelta(runId, round = 2, index = 1, delta = "answer", messages)

        assertEquals(
            listOf(
                "user-$runId",
                "assistant-$runId-1",
                "$runId-thinking-2-0",
                "$runId-tool-2-call_2",
                "assistant-$runId-2-1",
            ),
            messages.map { it.id }
        )
        val roundTwoAssistant = messages.last() as AgentMessageUi
        assertEquals("second roundanswer", roundTwoAssistant.content)
        assertTrue(roundTwoAssistant.isStreaming)
    }

    @Test
    fun keepsFallbackToolCallIdsDistinctAcrossRounds() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-fallback"
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "operate the phone"))

        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "tool_call_0",
                name = "search_apps",
                argsPreview = """{"query":"camera"}""",
            ),
            messages
        )
        messages = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "tool_call_0",
                name = "search_apps",
                resultSummary = "ok=true",
                imageCount = 0,
                imageBytes = 0,
            ),
            messages
        )
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "tool_call_0",
                name = "observe_screen",
                argsPreview = """{"include_screenshot":true}""",
            ),
            messages
        )

        val tools = messages.filterIsInstance<ToolActivityMessageUi>()
        assertEquals(2, tools.size)
        assertEquals("search_apps", tools[0].toolName)
        assertEquals(ToolActivityStatusUi.Success, tools[0].status)
        assertEquals("observe_screen", tools[1].toolName)
        assertEquals(ToolActivityStatusUi.Running, tools[1].status)
    }

    @Test
    fun toolActivityFollowsAssistantTextStreamedInSameRound() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-order"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "search")
        )

        messages = projector.appendTextDelta(runId, round = 1, index = 0, delta = "find the app first", messages)
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_1",
                name = "search_apps",
                argsPreview = "{}",
            ),
            messages
        )

        assertEquals(
            listOf(
                "user-$runId",
                "assistant-$runId-1-0",
                "$runId-tool-1-call_1",
            ),
            messages.map { it.id }
        )
    }

    @Test
    fun finalizingTextTrimsTrailingWhitespace() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-trim"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "hello")
        )

        messages = projector.appendTextDelta(runId, round = 1, index = 0, delta = "answer。\n\n", messages)
        messages = projector.finalizeTextRound(runId, round = 1, messages)

        val assistant = messages.last() as AgentMessageUi
        assertEquals("answer。", assistant.content)
        assertFalse(assistant.isStreaming)
    }

    @Test
    fun interruptedToolBecomesUnknownInsteadOfFailed() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-interrupted"
        val running = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                argsPreview = "execute command",
            ),
            listOf(UserMessageUi(id = "user-$runId", content = "restart the device")),
        )

        val interrupted = projector.interruptRunningTools("task interrupted", running)
        val tool = interrupted.filterIsInstance<ToolActivityMessageUi>().single()

        assertEquals(ToolActivityStatusUi.Unknown, tool.status)
        assertEquals("task interrupted", tool.resultSummary)
    }

    @Test
    fun keepsInterleavedReasoningTextAndHostedToolInEventOrder() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-interleaved"
        var messages: List<AgentChatMessageUi> = listOf(
            UserMessageUi(id = "user-$runId", content = "search the latest news"),
        )

        messages = projector.appendReasoningDelta(
            runId,
            round = 1,
            index = 0,
            delta = "first decide whether to search",
            messages,
        )
        messages = projector.appendTextDelta(
            runId,
            round = 1,
            index = 1,
            delta = "let me check first.",
            messages,
        )
        messages = projector.startHostedTool(
            runId,
            AgentEvent.HostedToolStarted(round = 1, toolCallId = "ws_1", name = "web search"),
            projector.finalizeTextRound(runId, round = 1, messages),
        )
        messages = projector.finishHostedTool(
            runId,
            AgentEvent.HostedToolFinished(
                round = 1,
                toolCallId = "ws_1",
                name = "web search",
                success = true,
            ),
            messages,
        )
        messages = projector.appendReasoningDelta(
            runId,
            round = 1,
            index = 2,
            delta = "organize search results",
            messages,
        )
        messages = projector.appendTextDelta(
            runId,
            round = 1,
            index = 3,
            delta = "this is the final answer.",
            messages,
        )

        assertEquals(
            listOf(
                "user-$runId",
                "$runId-thinking-1-0",
                "assistant-$runId-1-1",
                "$runId-tool-1-ws_1",
                "$runId-thinking-1-2",
                "assistant-$runId-1-3",
            ),
            messages.map { it.id },
        )
        assertFalse((messages[1] as ThinkingMessageUi).isStreaming)
        assertFalse((messages[2] as AgentMessageUi).isStreaming)
        assertEquals(ToolActivityStatusUi.Success, (messages[3] as ToolActivityMessageUi).status)
        assertFalse((messages[4] as ThinkingMessageUi).isStreaming)
        assertTrue((messages[5] as AgentMessageUi).isStreaming)
    }

    @Test
    fun compactionResultMergesIntoExistingMarkerInsteadOfAppending() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-compact"
        val started = SystemNoticeMessageUi(
            id = "assistant-$runId-compaction-op-1",
            code = SystemNoticeCode.ContextCompaction,
            detail = AgentEvent.ContextCompaction.RUNNING_DETAIL,
            running = true,
        )
        val completed = started.copy(
            detail = "context compacted: about 45,312 → 25,768 tokens",
            contextTokens = 25_768,
            running = false,
        )
        val base = listOf(UserMessageUi(id = "user-$runId", content = "organize context"))

        // a successful result keeps the token info in the event marker and does not append a second result notice.
        val success = AgentRunMessageProjector.mergeCompactionResultNotice(
            runId, base + completed, ok = true, detail = "context compaction complete",
        )
        assertEquals(base + completed, success)

        // a failed result rewrites the same marker into the failure reason.
        val failed = AgentRunMessageProjector.mergeCompactionResultNotice(
            runId, base + completed, ok = false, detail = "context compaction failed",
        )
        assertEquals("context compaction failed", failed.filterIsInstance<SystemNoticeMessageUi>().single().detail)

        // only when the event marker is missing (e.g. the disconnect-recovery path) is a result marker added.
        val appended = AgentRunMessageProjector.mergeCompactionResultNotice(
            runId, base, ok = true, detail = "context compaction complete",
        )
        assertEquals(
            "assistant-$runId-compaction-result",
            appended.filterIsInstance<SystemNoticeMessageUi>().single().id,
        )

        // a marker still in progress is finalized by the result and does not stay in the running state.
        val finalized = AgentRunMessageProjector.mergeCompactionResultNotice(
            runId, base + started, ok = true, detail = "context compaction complete",
        )
        val finishedNotice = finalized.filterIsInstance<SystemNoticeMessageUi>().single()
        assertEquals("context compaction complete", finishedNotice.detail)
        assertFalse(finishedNotice.running)

        // finishContextCompaction only finalizes markers that are still running; completed markers keep their token info.
        val interrupted = projector.finishContextCompaction(runId, base + started, "context compaction interrupted")
        val interruptedNotice = interrupted.filterIsInstance<SystemNoticeMessageUi>().single()
        assertEquals("context compaction interrupted", interruptedNotice.detail)
        assertFalse(interruptedNotice.running)
        assertEquals(base + completed, projector.finishContextCompaction(runId, base + completed, "context compaction interrupted"))
    }

    @Test
    fun finalizingRunDoesNotOverwriteCompletedThinkingElapsedSecondsAcrossRounds() {
        var now = 1_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val runId = "run-multi-round"
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "troubleshoot the issue"))

        messages = projector.applyEvent(
            runId,
            AgentEvent.AssistantBlockStart(1, AgentEvent.AssistantBlockKind.THINKING, 0),
            messages,
        )
        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "first round thinking", messages)
        now = 4_000L
        messages = projector.startTool(
            runId,
            AgentEvent.ToolStarted(round = 1, toolCallId = "call_1", name = "observe_screen", argsPreview = "{}"),
            projector.finalizeThinkingRound(runId, round = 1, messages),
        )
        messages = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_1",
                name = "observe_screen",
                resultSummary = "ok=true",
                imageCount = 0,
                imageBytes = 0,
                success = true,
            ),
            messages,
        )

        now = 64_000L
        messages = projector.applyEvent(
            runId,
            AgentEvent.AssistantBlockStart(2, AgentEvent.AssistantBlockKind.THINKING, 0),
            messages,
        )
        messages = projector.appendReasoningDelta(runId, round = 2, index = 0, delta = "second round thinking", messages)
        now = 66_000L
        messages = projector.appendTextDelta(runId, round = 2, index = 1, delta = "troubleshooting complete", messages)
        messages = projector.finalizeRun(runId, messages)

        val thinkings = messages.filterIsInstance<ThinkingMessageUi>()
        assertEquals(2, thinkings.size)
        assertEquals(3, thinkings[0].elapsedSeconds)
        assertEquals(2, thinkings[1].elapsedSeconds)
    }

    @Test
    fun interleavedThinkingInSameRoundKeepsEarlierBlockElapsedSeconds() {
        var now = 1_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val runId = "run-same-round"
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "look up material"))

        messages = projector.appendReasoningDelta(runId, round = 1, index = 0, delta = "judge first", messages)
        now = 3_000L
        messages = projector.appendTextDelta(runId, round = 1, index = 1, delta = "let me check.", messages)
        now = 13_000L
        messages = projector.appendReasoningDelta(runId, round = 1, index = 2, delta = "summarize again", messages)
        now = 16_000L
        messages = projector.finalizeThinkingRound(runId, round = 1, messages)

        val thinkings = messages.filterIsInstance<ThinkingMessageUi>()
        assertEquals(2, thinkings.size)
        assertEquals(2, thinkings[0].elapsedSeconds)
        assertEquals(3, thinkings[1].elapsedSeconds)
    }

    @Test
    fun replayRestoresCompletedThinkingDurationAndResumesInProgressClock() {
        var now = 100_000L
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { now })
        val runId = "run-replay-clock"
        val replayEpochMs = 92_000L
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi(id = "user-$runId", content = "continue the task"))

        val replayEvents = listOf(
            AgentEvent.AssistantBlockStart(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                startedAtEpochMs = 10_000L,
            ),
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 4,
                delta = "first round",
            ),
            AgentEvent.AssistantBlockEnd(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                contentChars = 4,
                elapsedMs = 4_500L,
            ),
            AgentEvent.AssistantBlockStart(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                startedAtEpochMs = 80_000L,
            ),
            AgentEvent.AssistantBlockDelta(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.THINKING,
                index = 0,
                deltaChars = 4,
                delta = "second round",
            ),
        )

        messages = projector.resetForReplay(runId, messages)
        replayEvents.forEach { event ->
            messages = projector.applyEvent(runId, event, messages, replayEpochMs = replayEpochMs)
        }

        val replayed = messages.filterIsInstance<ThinkingMessageUi>()
        assertEquals(2, replayed.size)
        assertFalse(replayed[0].isStreaming)
        assertEquals(4, replayed[0].elapsedSeconds)
        assertTrue(replayed[1].isStreaming)
        assertEquals(12, replayed[1].elapsedSeconds)

        now = 103_000L
        messages = projector.finalizeThinking(runId, messages)
        val settled = messages.filterIsInstance<ThinkingMessageUi>()
        assertEquals(4, settled[0].elapsedSeconds)
        assertEquals(15, settled[1].elapsedSeconds)
    }

    @Test
    fun subagentProgressIgnoresCorruptDuplicateAndPostTerminalSnapshots() {
        val projector = AgentRunMessageProjector(nowElapsedRealtime = { 1_000L })
        val runId = "run-subagent"
        val initial = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot(
            subagentId = "sub_1",
            subagentType = "explore",
            description = "check logs",
            status = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot.STATUS_RUNNING,
            currentAction = "starting…",
        ).encodeForWire()
        val runningStep = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot(
            subagentId = "sub_1",
            subagentType = "explore",
            description = "check logs",
            status = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot.STATUS_RUNNING,
            currentAction = "reading logs",
            totalToolSteps = 3,
        ).encodeForWire()
        val completed = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot(
            subagentId = "sub_1",
            subagentType = "explore",
            description = "check logs",
            status = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot.STATUS_COMPLETED,
            currentAction = "done · 3 tool calls",
            totalToolSteps = 3,
            output = "root cause located",
        ).encodeForWire()

        var messages: List<AgentChatMessageUi> = projector.startTool(
            runId,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call_sub",
                name = "subagent",
                argsPreview = "explore · check logs",
                command = initial,
            ),
            listOf(UserMessageUi(id = "user-$runId", content = "help me check the logs")),
        )

        // normal progress update.
        val progressEvent = AgentEvent.SubagentProgress(1, "call_sub", "sub_1", "explore", runningStep)
        messages = projector.applyEvent(runId, progressEvent, messages)
        assertEquals(runningStep, (messages.last() as ToolActivityMessageUi).command)

        // duplicate progress returns the same reference.
        assertSame(messages, projector.applyEvent(runId, progressEvent, messages))

        // corrupted snapshots are ignored, keeping the previous valid snapshot.
        val corruptEvent = progressEvent.copy(snapshotJson = "{\"subagentId\":\"sub_1\",\"broken...")
        assertSame(messages, projector.applyEvent(runId, corruptEvent, messages))
        assertEquals(runningStep, (messages.last() as ToolActivityMessageUi).command)

        // a corrupted terminal command does not overwrite the previous valid snapshot.
        val finishedWithCorrupt = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_sub",
                name = "subagent",
                resultSummary = "done",
                imageCount = 0,
                imageBytes = 0,
                success = true,
                command = "{\"truncated",
            ),
            messages,
        )
        assertEquals(runningStep, (finishedWithCorrupt.last() as ToolActivityMessageUi).command)

        // after a valid terminal update, late intermediate progress no longer writes back to override.
        val finishedValid = projector.finishTool(
            runId,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call_sub",
                name = "subagent",
                resultSummary = "done · 3 tool calls",
                imageCount = 0,
                imageBytes = 0,
                success = true,
                command = completed,
            ),
            messages,
        )
        assertEquals(completed, (finishedValid.last() as ToolActivityMessageUi).command)
        assertSame(finishedValid, projector.applyEvent(runId, progressEvent, finishedValid))
    }
}
