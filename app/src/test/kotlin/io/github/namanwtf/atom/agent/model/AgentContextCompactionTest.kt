package io.github.namanwtf.atom.agent.model

import io.github.namanwtf.atom.agent.runtime.AgentEvent
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.agent.runtime.AgentRuntimeRunExecutor
import io.github.namanwtf.atom.agent.runtime.AgentRuntimeWire
import io.github.namanwtf.atom.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextCompactionTest {
    @Test
    fun automaticScreenContentIsNotCopiedIntoCompactionInput() {
        val messages = jsonHistory()
        val user = AgentConversationCodec.userTextMessage("keep the latest question")
        AssistantScreenContextProjection.attach(user, "PRIVATE_AUTOMATIC_SCREEN_CONTENT")
        messages.put(user)
        val compacted = AgentContextCompactor(config, provider { request, _ ->
            assertFalse(request.messages.toString().contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
            response("The previous task is done.")
        }, AgentRunController()).compact(messages, 1, emptySet())
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(
            AgentConversationCodec.transcript(compacted, 1),
        ).contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
        assertTrue(AssistantScreenContextProjection.project(compacted).toString().contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
    }

    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixed constraint",
        contextWindow = 128_000,
    )

    @Test
    fun largeHistoryWithoutUsageDoesNotTriggerCompaction() {
        val original = "historical facts".repeat(40_000)
        var requests = 0
        val result = AgentModelClient.complete(config, "continue", AgentModelClient.ToolExecutor { error("must not execute a tool") },
            history = listOf(AgentModelClient.ConversationMessage("assistant", original)),
            provider = provider { request, _ ->
                requests++
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                assertTrue(request.messages.toString().contains(original))
                response("done")
            })
        assertEquals(1, requests)
        assertNull(result.contextSnapshot)
    }

    @Test
    fun interruptedToolBatchKeepsCompletedResultAndMarksOnlyUnknownResults() {
        val call = AgentModelClient.ConversationMessage("assistant", "", toolCallsJson = """[
            {"id":"a","type":"function","function":{"name":"fixture","arguments":"{}"}},
            {"id":"b","type":"function","function":{"name":"fixture","arguments":"{}"}}]""")
        val completed = AgentModelClient.ConversationMessage("tool", "real completed result", toolCallId = "a")
        val restored = AgentToolBatchRecovery.completeInterrupted(listOf(call, completed))
        assertEquals(completed, restored[1])
        assertEquals("b", restored.last().toolCallId)
        assertTrue(restored.last().content.contains("TOOL_INTERRUPTED"))
        assertEquals(restored, AgentToolBatchRecovery.completeInterrupted(restored))
    }

    @Test
    fun automaticCompactionPreservesIncrementalTranscriptAndLatestRequest() {
        val history = (1..8).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "task $turn"),
            AgentModelClient.ConversationMessage("assistant", "fact $turn ".repeat(2200)),
        ) }
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val provider = provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                assertTrue(request.effectiveTools.length() > 0)
                response("Previous tasks are done; continue with the latest request.")
            } else {
                assertTrue(request.messages.toString().contains("the latest request must be kept"))
                assertTrue(request.messages.toString().contains("fixed constraint"))
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 45_000)))
                response("completed the latest request")
            }
        }
        val result = AgentModelClient.complete(
            config.copy(contextWindow = 50_000), "the latest request must be kept", AgentModelClient.ToolExecutor { error("must not execute a tool") },
            history = history, provider = provider, onContextSnapshot = snapshots::add, onEvent = events::add,
        )
        assertTrue(summaries > 0)
        assertEquals(listOf("completed the latest request"), result.transcript.map { it.content })
        val snapshot = checkNotNull(result.contextSnapshot)
        assertTrue(snapshot.messages.any { it.contextSummary })
        assertEquals("the latest request must be kept", snapshot.messages.last().content)
        assertEquals(9, snapshot.consumedUserTurns)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "completed" })
        assertTrue(snapshots.all { AgentContextSnapshot.decode(it.encode()) == it })
    }

    @Test
    fun manualCompactionDoesNotCreateUserOrAssistantTranscript() {
        val result = AgentModelClient.complete(
            config, "", AgentModelClient.ToolExecutor { error("must not execute a tool") },
            history = history(), compactOnly = true, provider = provider { request, _ ->
                assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
                response("Previous work is done; keep the follow-up question.")
            },
        )
        assertTrue(result.transcript.isEmpty())
        assertEquals("", result.content)
        assertNotNull(result.contextSnapshot)
        assertTrue(result.contextSnapshot!!.messages.none { it.role == "user" && it.content.isBlank() })
    }

    @Test
    fun truncatedSummaryNeverReplacesOriginalContext() {
        val original = jsonHistory()
        val before = original.toString()
        val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
            response("half summary", "length")
        }, AgentRunController(), { emptySet() }, {}, { fail("must not submit a snapshot") })
        assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
        assertEquals(before, original.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun cancellationAndPersistenceFailureKeepOriginalContext() {
        for (cancel in listOf(true, false)) {
            val original = jsonHistory()
            val before = original.toString()
            val controller = AgentRunController()
            val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
                if (cancel) controller.cancel()
                response("bounded summary")
            }, controller, { emptySet() }, {}, { throw IllegalStateException("fixture storage failure") })
            assertThrows(Exception::class.java) { session.compact(force = true) }
            assertEquals(before, original.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun summaryInputRedactsSensitiveToolsAndOpaqueReasoningAndImages() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "rule"))
        messages.put(AgentConversationCodec.userTextMessage("old task"))
        val assistant = JSONObject().put("role", "assistant").put("content", "read result")
            .put("reasoning_content", "PRIVATE_THINKING")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "sensitive").put("type", "function")
                .put("function", JSONObject().put("name", "read_secret").put("arguments", "SECRET_ARGUMENT"))))
        ResponsesEphemeralState.attachOutputItems(assistant, JSONArray().put(JSONObject().put("encrypted", "OPAQUE_SECRET")))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "sensitive").put("content", "SECRET_RESULT"))
        history().forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        val pending = AgentConversationCodec.userMessage("transient observation", listOf(
            AgentModelClient.ModelImage("data:image/png;base64,PRIVATE_IMAGE", "image/png", 10),
        )).put("_atom_observation", true)
        messages.put(pending)
        val compacted = AgentContextCompactor(config, provider { request, _ ->
            val text = request.messages.toString()
            listOf("SECRET_ARGUMENT", "SECRET_RESULT", "PRIVATE_THINKING", "OPAQUE_SECRET", "PRIVATE_IMAGE").forEach {
                assertFalse("summary contains sensitive data $it", text.contains(it))
            }
            response("The information has already been read; the original sensitive results were not kept.")
        }, AgentRunController()).compact(messages, 1, setOf("sensitive"))
        assertSame(pending, compacted.getJSONObject(compacted.length() - 1))
        assertFalse(compacted.toString().contains("OPAQUE_SECRET"))
    }

    @Test
    fun toolBatchCannotBeSplitUntilEveryResultIsPresent() {
        val call = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().apply {
            listOf("a", "b").forEach { put(JSONObject().put("id", it).put("type", "function")
                .put("function", JSONObject().put("name", "fixture").put("arguments", "{}"))) }
        })
        val messages = listOf(call,
            JSONObject().put("role", "tool").put("tool_call_id", "a"),
            JSONObject().put("role", "tool").put("tool_call_id", "b"),
            AgentConversationCodec.userTextMessage("continue"))
        assertFalse(AgentContextCompactor.canSplit(messages, 1))
        assertFalse(AgentContextCompactor.canSplit(messages, 2))
        assertTrue(AgentContextCompactor.canSplit(messages, 3))
    }

    @Test
    fun signedAnthropicToolRoundCannotCompactBeforeReturningToolResults() {
        val messages = jsonHistory()
        messages.put(AgentConversationCodec.userTextMessage("continue the query"))
        val assistant = JSONObject().put("role", "assistant").put("content", "")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "toolu_current")
                .put("type", "function").put("function", JSONObject().put("name", "device_info")
                    .put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "")
                .put("signature", "CURRENT_SIGNATURE"))
            .put(JSONObject().put("type", "tool_use").put("id", "toolu_current")
                .put("name", "device_info").put("input", JSONObject())))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_current")
            .put("content", "tool result"))
        val original = messages.toString()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            fail("history must not be rewritten before the signature is used")
            response("must not execute")
        }, AgentRunController(), { emptySet() }, {}, { fail("must not submit a snapshot") })

        val failure = assertThrows(AgentModelFailure::class.java) {
            session.compact(force = true)
        }
        assertEquals("ANTHROPIC_THINKING_CONTEXT_LOCKED", failure.code)
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun compactionDropsCompletedAnthropicSignaturesAfterChangingPrefix() {
        val messages = jsonHistory()
        messages.put(AgentConversationCodec.userTextMessage("completed tool task"))
        val assistant = JSONObject().put("role", "assistant").put("content", "reading")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "toolu_old")
                .put("type", "function").put("function", JSONObject().put("name", "device_info")
                    .put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "")
                .put("signature", "STALE_SIGNATURE"))
            .put(JSONObject().put("type", "tool_use").put("id", "toolu_old")
                .put("name", "device_info").put("input", JSONObject())))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_old")
            .put("content", "done"))
        messages.put(JSONObject().put("role", "assistant").put("content", "query complete"))
        messages.put(AgentConversationCodec.userTextMessage("new question"))

        val compacted = AgentContextCompactor(config, provider { _, _ ->
            response("The previous task is done.")
        }, AgentRunController()).compact(messages, 1, emptySet())

        assertFalse(compacted.toString().contains("STALE_SIGNATURE"))
        assertTrue(compacted.toString().contains("new question"))
        assertFalse(compacted.toString().contains("toolu_old"))
    }

    @Test
    fun overflowClassificationDoesNotTreatEveryBadRequestAsCapacityFailure() {
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            """{"error":{"code":"context_length_exceeded","message":"private fixture"}}""").code)
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            """{"error":{"type":"invalid_request_error","message":"prompt is too long: fixture"}}""").code)
        assertEquals("HTTP_400", AgentModelFailure.http(400,
            """{"error":{"message":"invalid tool parameter"}}""").code)
    }

    @Test
    fun explicitOverflowFailsWithoutCompactionOrReplay() {
        var normalRequests = 0
        var summaryRequests = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "continue", AgentModelClient.ToolExecutor { error("must not execute a tool") },
            history = history(), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaryRequests++
                    response("The previous task is done.")
                } else if (normalRequests++ == 0) {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "failed thinking"))
                    throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                } else response("done")
            })
        }
        assertEquals("CONTEXT_OVERFLOW", (failure.cause as AgentModelFailure).code)
        assertEquals(1, normalRequests)
        assertEquals(0, summaryRequests)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun hostedToolOverflowDoesNotReplayOrStartCompaction() {
        var requests = 0
        val error = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "query", AgentModelClient.ToolExecutor { error("must not execute a tool") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.HostedToolStarted("hosted", "web_search"))
                    throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                })
        }
        assertEquals(1, requests)
        assertFalse((error.cause as AgentModelFailure).retryable)
    }

    @Test
    fun summaryRequestIncludesAllCompletedHistoryInOneRequest() {
        var requests = 0
        val seen = mutableListOf<String>()
        val result = AgentContextCompactor(config, provider { request, _ ->
            requests++
            val historyText = request.messages.getJSONObject(1).getString("content")
            seen += historyText
            response("The previous work is done.")
        }, AgentRunController()).compact(jsonHistory(), 1, emptySet())
        assertEquals(1, requests)
        for (turn in 1..4) assertTrue(seen.any { it.contains("question $turn") })
        assertTrue(result.toString().contains("question 6"))
    }

    @Test
    fun completedAnswerRemainsSuccessfulWhenFinalCompactionFails() {
        val events = mutableListOf<AgentEvent>()
        val finalText = "complete result".repeat(17_000)
        val result = AgentModelClient.complete(config.copy(contextWindow = 80_000), "continue", AgentModelClient.ToolExecutor { error("must not execute a tool") },
            history = history(), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) response("incomplete", "length")
                else {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 70_000)))
                    response(finalText)
                }
            }, onEvent = events::add)
        assertEquals(finalText, result.content)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "failed" })
        assertNotNull(result.contextSnapshot)
        assertEquals(finalText, result.contextSnapshot!!.messages.last().content)
        assertEquals(finalText, result.transcript.last().content)
    }

    @Test
    fun compactionReusesMainPrefixToolsAndSessionIdWithoutLeakingObservationsOrScreenContext() {
        val history = (1..4).flatMap { turn ->
            listOf(
                AgentModelClient.ConversationMessage("user", "historical task $turn"),
                AgentModelClient.ConversationMessage("assistant", "historical response $turn ".repeat(1500)),
            )
        }
        val events = mutableListOf<AgentEvent>()
        val capturedRequests = mutableListOf<ProviderRequest>()
        var round = 0
        val provider = provider { request, emit ->
            capturedRequests += ProviderRequest(
                config = request.config,
                messages = JSONArray(request.messages.toString()),
                tools = JSONArray(request.tools.toString()),
                sessionId = request.sessionId,
                purpose = request.purpose,
            )
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                emit(
                    ProviderEvent.Usage(
                        AgentTokenUsage(
                            contextTokens = 45_500,
                            inputTokens = 45_500,
                            outputTokens = 180,
                            cachedTokens = 44_000,
                            cacheWriteTokens = 300,
                        ),
                    ),
                )
                response("archived previous history and tool results.\nkey entities and clues: get_current_context")
            } else if (round++ == 0) {
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 45_000, cachedTokens = 40_000)))
                ProviderResponse(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", "")
                        .put("finish_reason", "tool_calls")
                        .put(
                            "tool_calls",
                            JSONArray().put(
                                JSONObject()
                                    .put("id", "call-1")
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", "get_current_context")
                                            .put("arguments", "{}"),
                                    ),
                            ),
                        ),
                )
            } else {
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 4_000, cachedTokens = 2_000)))
                response("all complete")
            }
        }
        val result = AgentModelClient.complete(
            config = config.copy(contextWindow = 50_000),
            prompt = "current question",
            toolExecutor = AgentModelClient.ToolExecutor {
                AgentModelClient.ToolResult(
                    content = """{"ok":true}""",
                    images = listOf(
                        AgentModelClient.ModelImage("data:image/png;base64,PRIVATE_OBS_IMAGE", "image/png", 16),
                    ),
                )
            },
            history = history,
            provider = provider,
            sessionId = "stable-conv-session",
            assistantScreenContext = "PRIVATE_AUTOMATIC_SCREEN_CONTENT",
            onEvent = events::add,
        )

        assertEquals("all complete", result.content)
        val mainRound1 = capturedRequests.first { it.purpose == ProviderRequestPurpose.CHAT }
        val compactionReq = capturedRequests.first { it.purpose == ProviderRequestPurpose.COMPACTION }
        assertEquals("stable-conv-session", compactionReq.sessionId)
        assertEquals(mainRound1.effectiveTools.toString(), compactionReq.effectiveTools.toString())
        assertEquals(mainRound1.messages.getJSONObject(0).toString(), compactionReq.messages.getJSONObject(0).toString())
        assertFalse(compactionReq.messages.toString().contains("PRIVATE_OBS_IMAGE"))
        assertFalse(compactionReq.messages.toString().contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
        assertTrue(compactionReq.messages.toString().contains("historical task 1"))
        assertTrue(compactionReq.messages.toString().contains("current question"))
        assertEquals(
            true,
            compactionReq.messages.getJSONObject(compactionReq.messages.length() - 1)
                .optBoolean("_atom_compaction_prompt"),
        )
        val completedCompaction = events.filterIsInstance<AgentEvent.ContextCompaction>()
            .single { it.phase == AgentEvent.ContextCompaction.PHASE_COMPLETED }
        assertEquals("fold-1", completedCompaction.archiveId)
        assertEquals(45_500, completedCompaction.usage?.inputTokens)
        assertEquals(44_000, completedCompaction.usage?.cachedTokens)
        assertEquals(300, completedCompaction.usage?.cacheWriteTokens)
        assertEquals(AgentTokenUsage.REQUEST_KIND_COMPACTION, completedCompaction.usage?.requestKind)
        val snapshot = checkNotNull(result.contextSnapshot)
        val summaryMsg = snapshot.messages.first { it.contextSummary }
        assertEquals("fold-1", summaryMsg.compactionArchiveId)
        assertTrue(summaryMsg.content.contains("fold-1"))
        assertTrue(summaryMsg.content.contains(AgentConversationToolCatalog.READ_HISTORY))
    }

    @Test
    fun repeatedCompactionsIncrementArchiveIdAndFallBackIfModelAttemptsToolCall() {
        val firstMessages = jsonHistory()
        val firstCompacted = AgentContextCompactor(
            config = config,
            provider = provider { _, _ -> response("first-stage summary.") },
            controller = AgentRunController(),
            mainTools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            mainSessionId = "s1",
            enableCacheReuse = true,
        ).compactWithOutcome(firstMessages, 1, emptySet())
        assertEquals("fold-1", firstCompacted.archiveId)

        val secondMessages = JSONArray(firstCompacted.messages.toString()).apply {
            put(AgentConversationCodec.userTextMessage("a new round of tasks"))
            put(JSONObject().put("role", "assistant").put("content", "subsequent result ".repeat(1500)))
        }
        var attempts = 0
        val secondCompacted = AgentContextCompactor(
            config = config,
            provider = provider { request, emit ->
                attempts++
                if (attempts == 1) {
                    assertTrue(request.tools.length() > 0)
                    emit(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, 0, "c1", "get_current_context"))
                    response("")
                } else {
                    assertEquals(0, request.tools.length())
                    response("merged second-stage summary.")
                }
            },
            controller = AgentRunController(),
            mainTools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            mainSessionId = "s1",
            enableCacheReuse = true,
        ).compactWithOutcome(secondMessages, 1, emptySet())
        assertEquals(2, attempts)
        assertEquals("fold-2", secondCompacted.archiveId)
        assertTrue(secondCompacted.messages.toString().contains("fold-2"))
    }

    @Test
    fun manualCompactionIncludesConversationHistoryInRunToolsMatchingChat() {
        assertTrue(AgentRuntimeRunExecutor.supportsHistoryTool(AgentRuntimeWire.OP_CHAT))
        assertTrue(AgentRuntimeRunExecutor.supportsHistoryTool(AgentRuntimeWire.OP_COMPACT))
        assertFalse(AgentRuntimeRunExecutor.supportsHistoryTool(AgentRuntimeWire.OP_REWRITE_REPLY))

        val chatAdditionalTools = AgentRuntimeRunExecutor.buildRunTools(
            mcpTools = JSONArray(),
            subagentAllowed = true,
            includeHistoryTool = AgentRuntimeRunExecutor.supportsHistoryTool(AgentRuntimeWire.OP_CHAT),
            includeCharacterMemoryTools = false,
        )
        val compactAdditionalTools = AgentRuntimeRunExecutor.buildRunTools(
            mcpTools = JSONArray(),
            subagentAllowed = true,
            includeHistoryTool = AgentRuntimeRunExecutor.supportsHistoryTool(AgentRuntimeWire.OP_COMPACT),
            includeCharacterMemoryTools = false,
        )
        assertEquals(chatAdditionalTools.toString(), compactAdditionalTools.toString())

        val capturedRequests = mutableListOf<ProviderRequest>()
        val recordingProvider = provider { request, _ ->
            capturedRequests += request
            response("Done.")
        }

        AgentModelClient.complete(
            config = config,
            prompt = "continue",
            toolExecutor = AgentModelClient.ToolExecutor { error("must not execute a tool") },
            history = history(),
            provider = recordingProvider,
            sessionId = "manual-compact-session",
            additionalTools = chatAdditionalTools,
        )
        AgentModelClient.complete(
            config = config,
            prompt = "",
            toolExecutor = AgentModelClient.ToolExecutor { error("must not execute a tool") },
            history = history(),
            provider = recordingProvider,
            sessionId = "manual-compact-session",
            compactOnly = true,
            additionalTools = compactAdditionalTools,
        )

        val chatRequest = capturedRequests.first { it.purpose == ProviderRequestPurpose.CHAT }
        val compactRequest = capturedRequests.first { it.purpose == ProviderRequestPurpose.COMPACTION }
        val compactToolNames = (0 until compactRequest.effectiveTools.length()).map { index ->
            compactRequest.effectiveTools.getJSONObject(index).getJSONObject("function").getString("name")
        }
        assertTrue(AgentConversationToolCatalog.READ_HISTORY in compactToolNames)
        assertEquals(chatRequest.effectiveTools.toString(), compactRequest.effectiveTools.toString())
    }

    private fun history() = (1..6).flatMap { turn -> listOf(
        AgentModelClient.ConversationMessage("user", "question $turn"),
        AgentModelClient.ConversationMessage("assistant", "fact ".repeat(1000)),
    ) }

    private fun jsonHistory() = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed rule")).also {
        history().forEach { message -> it.put(AgentConversationCodec.toJsonObject(message)) }
    }

    private fun response(text: String, stop: String = "stop") = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", stop),
    )

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            block(request, onEvent)
    }
}
