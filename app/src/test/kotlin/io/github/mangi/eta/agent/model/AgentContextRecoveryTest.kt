package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextRecoveryTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixed constraint",
        contextWindow = 900_000,
    )

    @Test
    fun unknownWindowStillRunsButNeverAutoCompacts() {
        for (window in listOf(null, 0, -1)) {
            var summaries = 0
            val result = AgentModelClient.complete(config.copy(contextWindow = window), "continue",
                AgentModelClient.ToolExecutor { error("must not execute a tool") },
                history = history(), provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("summary")
                    } else {
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 10_000_000)))
                        response("done")
                    }
                })
            assertEquals("done", result.content)
            assertEquals(0, summaries)
        }
    }

    @Test
    fun automaticCompactionUsesOnlyActualInputUsageAndConfiguredWindow() {
        for (input in listOf(null, 300_000, 764_999, 765_000)) {
            var summaries = 0
            val events = mutableListOf<AgentEvent>()
            val result = AgentModelClient.complete(config, "continue",
                AgentModelClient.ToolExecutor { error("must not execute a tool") },
                history = history(), onEvent = events::add, provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("The previous task is done.")
                    } else {
                        assertTrue(request.messages.toString().contains("x".repeat(100)))
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = input, outputTokens = 800_000)))
                        response("done")
                    }
                })
            assertEquals(if (input == 765_000) 1 else 0, summaries)
            assertEquals("done", result.content)
            val completed = events.filterIsInstance<AgentEvent.ContextCompaction>().lastOrNull()
            if (input == 765_000) {
                assertEquals(765_000, completed!!.tokensBefore)
                assertNull(completed.tokensAfter)
            } else assertNull(completed)
        }
    }

    @Test
    fun disabledAutomaticCompactionStillAllowsManualCompaction() {
        val disabled = config.copy(autoCompactionEnabled = false)
        var summaries = 0
        val events = mutableListOf<AgentEvent>()
        val provider = provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                response("The previous task is done.")
            } else {
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                response("done")
            }
        }
        val answer = AgentModelClient.complete(disabled, "continue", AgentModelClient.ToolExecutor { error("must not execute a tool") },
            history = history(), provider = provider, onEvent = events::add)
        assertEquals("done", answer.content)
        assertEquals(0, summaries)
        assertTrue(events.none { it is AgentEvent.ContextCompaction })
        assertNull(answer.contextSnapshot)

        val manual = AgentModelClient.complete(disabled, "", AgentModelClient.ToolExecutor { error("must not execute a tool") },
            history = history(), provider = provider, compactOnly = true)
        assertEquals(1, summaries)
        assertNotNull(manual.contextSnapshot)
        assertTrue(manual.transcript.isEmpty())
    }

    @Test
    fun compactionDoesNotRepeatUntilNewUsageArrives() {
        val messages = messages()
        var summaries = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            summaries++
            response("History is done.")
        }, AgentRunController(), { emptySet() }, {}, {})
        session.observeInputTokens(765_000)
        session.compact()
        session.compact()
        assertEquals(1, summaries)
        assertNotNull(session.snapshot())
    }

    @Test
    fun summaryFailureDoesNotSplitOrRetryAndKeepsOriginalHistory() {
        for (code in listOf("CONTEXT_OVERFLOW", "MODEL_TIMEOUT")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                throw AgentModelFailure(code, true, "fixture")
            }, AgentRunController(), { emptySet() }, {}, { fail("a failed summary must not be submitted") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals(code, failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun invalidSummaryDoesNotRetryAndKeepsOriginalHistory() {
        for ((content, stop) in listOf("" to "stop", "half" to "length", "x".repeat(12_001) to "stop")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                response(content, stop)
            }, AgentRunController(), { emptySet() }, {}, { fail("must not submit an invalid summary") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals("CONTEXT_SUMMARY_INVALID", failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
        }
    }

    @Test
    fun emptyLengthResponseIsNotReplayedEvenWhenInputIsNearWindow() {
        var requests = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "continue", AgentModelClient.ToolExecutor { error("must not execute a tool") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    response("", "length")
                })
        }
        assertEquals("MODEL_OUTPUT_LIMIT", (failure.cause as AgentModelFailure).code)
        assertEquals(1, requests)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun failedChatAttemptUsageCannotTriggerCompactionAfterRetry() {
        var requests = 0
        val loop = AgentLoop(
            config, messages(), JSONArray(), provider { request, emit ->
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                if (++requests == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    throw AgentModelFailure.incompleteStream("fixture")
                }
                response("done")
            }, AgentModelClient.ToolExecutor { error("must not execute a tool") }, AgentRunController(), AgentTraceFormatter(), {},
            modelRetry = AgentModelRetry { _, _ -> }, systemCount = 1,
        )
        assertEquals("done", loop.run().content)
        assertEquals(2, requests)
        assertNull(loop.contextSnapshot())
    }

    @Test
    fun summaryCancellationKeepsOriginalContext() {
        val messages = messages()
        val original = messages.toString()
        val controller = AgentRunController()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            controller.cancel()
            response("must not submit")
        }, controller, { emptySet() }, {}, { fail("cannot submit when canceled") })
        assertThrows(Exception::class.java) { session.compact(force = true) }
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun onlySummaryRequestsHaveAnOverallTimeout() {
        assertEquals(300_000, AgentHttpClient.modelClient(ProviderRequestPurpose.COMPACTION).callTimeoutMillis)
        assertEquals(0, AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT).callTimeoutMillis)
    }

    private fun history() = listOf(
        AgentModelClient.ConversationMessage("user", "old task"),
        AgentModelClient.ConversationMessage("assistant", "x".repeat(780_000)),
    )

    private fun messages() = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed constraint")).also { source ->
        history().forEach { source.put(AgentConversationCodec.toJsonObject(it)) }
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
