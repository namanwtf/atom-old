package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatCompletionsProviderTest {
    @Test
    fun thinkingStreamsBeforeDoneAndClosesBeforeTheUsageTail() {
        val firstDelta = CountDownLatch(1)
        val textEnded = CountDownLatch(1)
        val endedBeforeTail = AtomicBoolean(false)
        withSseServer(
            body = "",
            writeBody = { output ->
                output.write(sseChunk(JSONObject().put("reasoning_content", "Analyze first, ")).toByteArray())
                output.flush()
                check(firstDelta.await(5, TimeUnit.SECONDS))
                output.write(sseChunk(
                    JSONObject().put("reasoning_content", "then answer.").put("content", "final answer"),
                    finishReason = "stop",
                ).toByteArray())
                output.flush()
                endedBeforeTail.set(textEnded.await(5, TimeUnit.SECONDS))
                check(endedBeforeTail.get())
                output.write((usageChunk(JSONObject().put("total_tokens", 18)) + "data: [DONE]\n\n").toByteArray())
            },
        ) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), AgentRunController()) { event ->
                events += event
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.THINKING) {
                    firstDelta.countDown()
                }
                if (event is ProviderEvent.BlockEnd && event.kind == AssistantBlockKind.TEXT) {
                    textEnded.countDown()
                }
            }

            assertEquals("Analyze first, then answer.", response.assistantMessage.getString("reasoning_content"))
            assertTrue(endedBeforeTail.get())
            assertEquals("final answer", response.assistantMessage.getString("content"))
            assertEquals(listOf("Analyze first, ", "then answer."), events.filterIsInstance<ProviderEvent.BlockDelta>()
                .filter { it.kind == AssistantBlockKind.THINKING }.map { it.delta })
            assertEquals(listOf(AssistantBlockKind.THINKING, AssistantBlockKind.TEXT),
                events.filterIsInstance<ProviderEvent.BlockEnd>().map { it.kind })
            assertEquals(18, events.filterIsInstance<ProviderEvent.Usage>().single().usage.contextTokens)
        }
    }

    @Test
    fun reasoningAliasesAndStructuredDetailsDisplayOnceWithoutOpaqueFields() {
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "first paragraph")
                .put("reasoning", "first paragraph").put("reasoning_details", JSONArray().put(
                    JSONObject().put("type", "reasoning.text").put("text", "first paragraph"),
                ))))
            append(sseChunk(JSONObject().put("reasoning_content", JSONObject.NULL).put("reasoning", "second paragraph")))
            append(sseChunk(JSONObject().put("reasoning_content", "").put("reasoning", JSONObject.NULL)
                .put("reasoning_details", JSONArray()
                    .put(JSONObject().put("type", "reasoning.text").put("text", "third paragraph").put("signature", "opaque-signature"))
                    .put(JSONObject().put("type", "reasoning.encrypted").put("data", "opaque-data"))
                    .put(JSONObject().put("type", "reasoning.summary").put("summary", "summary")))))
            append(sseChunk(JSONObject().put("content", "answer"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }
        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), AgentRunController(), events::add)

            assertEquals("first, second, third, summary", response.assistantMessage.getString("reasoning_content"))
            assertEquals(listOf("first paragraph", "second paragraph", "third-stage summary"), events.filterIsInstance<ProviderEvent.BlockDelta>()
                .filter { it.kind == AssistantBlockKind.THINKING }.map { it.delta })
            assertEquals("first, second, third, summary", events.filterIsInstance<ProviderEvent.BlockEnd>()
                .single { it.kind == AssistantBlockKind.THINKING }.content)
        }
    }

    @Test
    fun multilineDataFramesPreserveThinkingAndText() {
        val payload = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject()
            .put("reasoning_content", "analyze\ncontinue").put("content", "answer")))).toString(2)
        val body = ": keepalive\r\n\r\n" +
            payload.lines().joinToString("\r\n") { "data: $it" } + "\r\n\r\ndata: [DONE]\r\n\r\n"
        withSseServer(body) { baseUrl ->
            val response = OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), AgentRunController())
            assertEquals("analyze\ncontinue", response.assistantMessage.getString("reasoning_content"))
            assertEquals("answer", response.assistantMessage.getString("content"))
        }
    }

    @Test
    fun completeParsesTextDeltasWithDoneSentinel() {
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "Hel")))
            append(sseChunk(JSONObject().put("content", "lo"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("Hello", response.assistantMessage.getString("content"))
            assertEquals(
                "Hello",
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.TEXT }
                    .joinToString("") { it.delta }
            )
        }
    }

    @Test
    fun completeSplitsVisibleBlocksWhenDeltaTypeChanges() {
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "analyze first")))
            append(sseChunk(JSONObject().put("content", "explain first")))
            append(sseChunk(JSONObject().put("reasoning_content", "then confirm")))
            append(sseChunk(JSONObject().put("content", "final answer"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add,
            )

            assertEquals(
                listOf(
                    "start:THINKING:0",
                    "delta:THINKING:0:analyze first",
                    "end:THINKING:0",
                    "start:TEXT:1",
                    "delta:TEXT:1:explain first",
                    "end:TEXT:1",
                    "start:THINKING:2",
                    "delta:THINKING:2:then confirm",
                    "end:THINKING:2",
                    "start:TEXT:3",
                    "delta:TEXT:3:final answer",
                    "end:TEXT:3",
                ),
                events.mapNotNull { event ->
                    when (event) {
                        is ProviderEvent.BlockStart -> "start:${event.kind}:${event.index}"
                        is ProviderEvent.BlockDelta -> "delta:${event.kind}:${event.index}:${event.delta}"
                        is ProviderEvent.BlockEnd -> "end:${event.kind}:${event.index}"
                        else -> null
                    }
                },
            )
        }
    }

    @Test
    fun completeAcceptsFinishReasonWhenServerClosesWithoutDone() {
        val usage = JSONObject()
            .put("prompt_tokens", 10)
            .put("completion_tokens", 2)
            .put("total_tokens", 12)
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "done")))
            append(sseChunk(null, finishReason = "stop"))
            append(usageChunk(usage))
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("done", response.assistantMessage.getString("content"))
            assertEquals(
                12,
                events.filterIsInstance<ProviderEvent.Usage>().single().usage.contextTokens
            )
        }
    }

    @Test
    fun completeRejectsOpenRouterMidStreamError() {
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "partial content")))
            append(
                sseErrorChunk(
                    code = 502,
                    message = "Provider disconnected unexpectedly",
                    errorType = "provider_unavailable",
                )
            )
        }

        withSseServer(body) { baseUrl ->
            val thrown = runCatching {
                OpenAiChatCompletionsProvider.complete(
                    request = providerRequest(baseUrl) {
                        it.copy(providerSourceType = "openrouter")
                    },
                    runController = AgentRunController()
                )
            }.exceptionOrNull()

            assertNotNull(thrown)
            assertTrue(thrown is IllegalStateException)
            assertTrue(thrown?.message.orEmpty().contains("Provider disconnected unexpectedly"))
            assertTrue(thrown?.message.orEmpty().contains("provider_unavailable"))
        }
    }

    @Test
    fun completeDoesNotRequestDeprecatedOpenRouterUsageOption() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(": OPENROUTER PROCESSING\n\n")
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl) {
                    it.copy(providerSourceType = "openrouter")
                },
                runController = AgentRunController(),
            )

            assertTrue(!JSONObject(requestBody.get()).has("stream_options"))
        }
    }

    @Test
    fun requestMergesSystemMessagesAtTheBeginningForStrictChatTemplates() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = requestBody::set) { baseUrl ->
            val request = providerRequest(baseUrl).copy(
                messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "base constraint"))
                    .put(JSONObject().put("role", "user").put("content", "old question"))
                    .put(JSONObject().put("role", "system").put("content", "dynamic context"))
                    .put(JSONObject().put("role", "assistant").put("content", "old answer"))
                    .put(JSONObject().put("role", "user").put("content", "current question")),
            )

            OpenAiChatCompletionsProvider.complete(request, AgentRunController())
        }

        val sent = JSONObject(requestBody.get()).getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), sent.roles())
        assertEquals("base constraint\n\ndynamic context", sent.getJSONObject(0).getString("content"))
    }

    @Test
    fun completeAccumulatesChunkedToolCalls() {
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "Need to call a tool.")))
            append(
                sseChunk(
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("id", "call_1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "term")
                                        .put("arguments", "{\"a\"")
                                )
                        )
                    )
                )
            )
            append(
                sseChunk(
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "inal")
                                        .put("arguments", ":1}")
                                )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            )
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            val toolCall = response.assistantMessage
                .getJSONArray("tool_calls")
                .getJSONObject(0)
            assertEquals("call_1", toolCall.getString("id"))
            assertEquals("terminal", toolCall.getJSONObject("function").getString("name"))
            assertEquals("{\"a\":1}", toolCall.getJSONObject("function").getString("arguments"))
            assertEquals("Need to call a tool.", response.assistantMessage.getString("reasoning_content"))
            assertEquals(
                "Need to call a tool.",
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.THINKING }
                    .joinToString("") { it.delta }
            )
            assertEquals(2, events.filterIsInstance<ProviderEvent.BlockDelta>().count { it.kind == AssistantBlockKind.TOOL_CALL })
        }
    }

    @Test
    fun completeParsesReasoningUsageAndMergesExtraBody() {
        val usage = JSONObject()
            .put("prompt_tokens", 10)
            .put("completion_tokens", 8)
            .put("total_tokens", 18)
            .put(
                "completion_tokens_details",
                JSONObject().put("reasoning_tokens", 5)
            )
            .put(
                "prompt_tokens_details",
                JSONObject().put("cached_tokens", 3)
            )
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "analyze first")))
            append(sseChunk(JSONObject().put("content", "result"), finishReason = "stop"))
            append(usageChunk(usage))
            append("data: [DONE]\n\n")
        }

        val requestBody = AtomicReference<String>()
        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(
                    baseUrl = baseUrl,
                    configTransform = {
                        it.copy(
                            thinkingEnabled = true,
                            extraBodyJson = """{"enable_thinking":false,"thinking_budget":50}"""
                        )
                    }
                ),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("result", response.assistantMessage.getString("content"))
            assertEquals("analyze first", response.assistantMessage.getString("reasoning_content"))
            val parsedUsage = events.filterIsInstance<ProviderEvent.Usage>().single().usage
            assertEquals(18, parsedUsage.contextTokens)
            assertEquals(10, parsedUsage.inputTokens)
            assertEquals(8, parsedUsage.outputTokens)
            assertEquals(5, parsedUsage.reasoningTokens)
            assertEquals(3, parsedUsage.cachedTokens)

            val request = JSONObject(requestBody.get())
            assertEquals(false, request.getBoolean("enable_thinking"))
            assertEquals(50, request.getInt("thinking_budget"))
            assertTrue(
                request.getJSONObject("stream_options").getBoolean("include_usage")
            )
        }
    }

    @Test
    fun completeRejectsStreamThatEndsBeforeDone() {
        val body = sseChunk(JSONObject().put("content", "partial"))

        withSseServer(body) { baseUrl ->
            val thrown = runCatching {
                OpenAiChatCompletionsProvider.complete(
                    request = providerRequest(baseUrl),
                    runController = AgentRunController()
                )
            }.exceptionOrNull()

            assertNotNull(thrown)
            assertTrue(thrown is IllegalStateException)
            assertTrue(thrown?.message.orEmpty().contains("did not end normally"))
        }
    }

    @Test
    fun completeBuildsDeepSeekThinkingRequest() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl) {
                    it.copy(
                        providerSourceType = "deepseek",
                        model = "deepseek-v4-pro",
                        thinkingEnabled = true,
                        reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.HIGH,
                    )
                },
                runController = AgentRunController(),
            )

            val request = JSONObject(requestBody.get())
            assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
            assertEquals("high", request.getString("reasoning_effort"))
        }
    }

    @Test
    fun completeBuildsKimiPreservedThinkingRequest() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl) {
                    it.copy(
                        providerSourceType = "moonshot",
                        model = "kimi-k2.6",
                        thinkingEnabled = true,
                    )
                },
                runController = AgentRunController(),
            )

            val thinking = JSONObject(requestBody.get()).getJSONObject("thinking")
            assertEquals("enabled", thinking.getString("type"))
            assertEquals("all", thinking.getString("keep"))
        }
    }

    @Test
    fun parseUsageCapturesDeepSeekCacheMissAndCacheWriteTokens() {
        val usage = JSONObject()
            .put("prompt_cache_hit_tokens", 9_000)
            .put("prompt_cache_miss_tokens", 1_000)
            .put("cache_creation_input_tokens", 250)
            .put("completion_tokens", 120)
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append(usageChunk(usage))
            append("data: [DONE]\n\n")
        }
        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), AgentRunController(), events::add)
            val parsed = events.filterIsInstance<ProviderEvent.Usage>().single().usage
            assertEquals(10_000, parsed.inputTokens)
            assertEquals(9_000, parsed.cachedTokens)
            assertEquals(250, parsed.cacheWriteTokens)
            assertEquals(120, parsed.outputTokens)
        }
    }

    private fun providerRequest(
        baseUrl: String,
        configTransform: (AgentModelClient.ModelConfig) -> AgentModelClient.ModelConfig = { it }
    ): ProviderRequest =
        ProviderRequest(
            config = configTransform(
                AgentModelClient.ModelConfig(
                    providerSourceType = "custom",
                    baseUrl = baseUrl,
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                    terminalTools = true
                )
            ),
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
            tools = JSONArray()
        )

    private fun sseChunk(delta: JSONObject?, finishReason: String? = null): String {
        val choice = JSONObject()
            .put("delta", delta ?: JSONObject.NULL)
            .put("finish_reason", finishReason ?: JSONObject.NULL)
        return "data: ${JSONObject().put("choices", JSONArray().put(choice))}\n\n"
    }

    private fun usageChunk(usage: JSONObject): String =
        "data: ${JSONObject().put("choices", JSONArray()).put("usage", usage)}\n\n"

    private fun sseErrorChunk(
        code: Int,
        message: String,
        errorType: String,
    ): String =
        "data: ${JSONObject()
            .put("error", JSONObject()
                .put("code", code)
                .put("message", message)
                .put("metadata", JSONObject().put("error_type", errorType)))
            .put("choices", JSONArray().put(
                JSONObject()
                    .put("delta", JSONObject().put("content", ""))
                    .put("finish_reason", "error")
            ))}\n\n"

    private fun withSseServer(
        body: String,
        onRequest: (String) -> Unit = {},
        writeBody: ((OutputStream) -> Unit)? = null,
        block: (String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/chat/completions") { exchange ->
            onRequest(exchange.requestBody.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, if (writeBody == null) bytes.size.toLong() else 0)
            exchange.responseBody.use { output ->
                if (writeBody == null) output.write(bytes) else writeBody(output)
            }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun JSONArray.roles(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("role") }
}
