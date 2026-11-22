package io.github.namanwtf.atom.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.data.model.CustomBody
import io.github.namanwtf.atom.data.model.ModelReasoningCapabilities
import io.github.namanwtf.atom.data.model.OpenAiEndpointMode
import io.github.namanwtf.atom.data.model.ReasoningEffort
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiResponsesProviderTest {
    @Test
    fun hostedSearchDeclarationExcludesSameNamedFunctionAndPreservesOtherTools() {
        val functions = JSONArray().also { AgentWebToolCatalog.appendTo(it) }
        for (enabled in listOf(false, true)) {
            val request = OpenAiResponsesProvider.buildRequestJson(
                config("https://example.com/v1").copy(hostedWebSearchEnabled = enabled),
                JSONArray(), functions,
            )
            val tools = request.getJSONArray("tools")
            val all = (0 until tools.length()).map { tools.getJSONObject(it) }
            assertEquals(if (enabled) 1 else 0, all.count { it.optString("type") == "web_search" })
            assertEquals(if (enabled) 0 else 1, all.count { it.optString("type") == "function" && it.optString("name") == "web_search" })
            assertEquals(1, all.count { it.optString("name") == "fetch_url" })
        }
        assertEquals("the original catalog must not be modified by Provider projection", 2, functions.length())
    }

    @Test
    fun nativeReasoningDeltasArriveBeforeDoneAndTypedFinalContentIsNotDuplicated() {
        val firstDeltaDelivered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val serverObservedCompletion = CountDownLatch(1)
        val completedBeforeClose = AtomicBoolean(false)
        val reasoningText = "First analyze the input, then organize the answer."
        val nativeItem = JSONObject()
            .put("id", "rs_native")
            .put("type", "reasoning")
            .put("summary", JSONArray())
            .put(
                "content",
                JSONArray().put(JSONObject().put("type", "reasoning_text").put("text", reasoningText)),
            )
        fun reasoningEvent(type: String, key: String, text: String) = event(
            type,
            JSONObject().put("item_id", "rs_native")
                .put("output_index", 0).put("content_index", 0).put(key, text),
        )
        val firstChunk = reasoningEvent("response.reasoning_text.delta", "delta", "First analyze the input, ")
        val remainingChunks = buildString {
            append(reasoningEvent("response.reasoning_text.delta", "delta", "then organize the answer."))
            append(reasoningEvent("response.reasoning_text.done", "text", reasoningText))
            append(event("response.output_item.done", JSONObject().put("output_index", 0).put("item", nativeItem)))
            append(responseTextEvent("response.output_text.delta", "msg_1", 1, "delta", "answer"))
            append(
                event(
                    "response.completed",
                    JSONObject().put(
                        "response",
                        JSONObject().put("status", "completed")
                            .put("output", JSONArray().put(nativeItem).put(messageItem("msg_1", "answer"))),
                    ),
                ),
            )
        }

        withSseServer(
            body = "",
            writeBody = { output ->
                output.write(firstChunk.toByteArray(Charsets.UTF_8))
                output.flush()
                // The server only sends done and the terminal state after confirming the first delta was delivered, ruling out output only on EOF.
                check(firstDeltaDelivered.await(5, TimeUnit.SECONDS))
                output.write(remainingChunks.toByteArray(Charsets.UTF_8))
                output.flush()
                completedBeforeClose.set(completed.await(5, TimeUnit.SECONDS))
                serverObservedCompletion.countDown()
            },
        ) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                ProviderRequest(
                    config(baseUrl),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "test reasoning")),
                    JSONArray(),
                ),
                AgentRunController(),
            ) { event ->
                events += event
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.THINKING) {
                    firstDeltaDelivered.countDown()
                }
                if (event is ProviderEvent.Completed) completed.countDown()
            }

            assertTrue(serverObservedCompletion.await(5, TimeUnit.SECONDS))
            assertTrue(completedBeforeClose.get())
            assertEquals(
                listOf("First analyze the input, ", "then organize the answer."),
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.THINKING }.map { it.delta },
            )
            val end = events.filterIsInstance<ProviderEvent.BlockEnd>()
                .filter { it.kind == AssistantBlockKind.THINKING }.single()
            assertEquals(reasoningText, end.content)
            assertFalse(end.replaceContent)
            assertEquals(reasoningText, result.assistantMessage.getString("reasoning_content"))
            assertEquals("answer", result.assistantMessage.getString("content"))
            assertEquals(
                nativeItem.toString(),
                ResponsesEphemeralState.outputItems(result.assistantMessage)?.getJSONObject(0)?.toString(),
            )
        }
    }

    @Test
    fun typedReasoningPartsKeepTheirIdentityAndOverrideLegacyAlias() {
        val item = JSONObject().put("id", "rs_parts").put("type", "reasoning")
            .put("reasoning_text", "the old field must not be appended again")
            .put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "reasoning_text").put("text", "first paragraph"))
                    .put(JSONObject().put("type", "reasoning_text").put("text", "second-stage completion")),
            )
        val body = buildString {
            append(
                event(
                    "response.reasoning_text.delta",
                    JSONObject().put("item_id", "rs_parts").put("output_index", 0)
                        .put("content_index", 1).put("delta", "second paragraph"),
                ),
            )
            append(
                event(
                    "response.completed",
                    JSONObject().put(
                        "response",
                        JSONObject().put("status", "completed").put("output", JSONArray().put(item)),
                    ),
                ),
            )
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                ProviderRequest(
                    config(baseUrl),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "test reasoning")),
                    JSONArray(),
                ),
                AgentRunController(),
                events::add,
            )

            assertEquals("first paragraph\nsecond-stage completion", result.assistantMessage.getString("reasoning_content"))
            val corrections = events.filterIsInstance<ProviderEvent.BlockEnd>().filter { it.replaceContent }
            assertEquals(1, corrections.size)
            assertEquals(0, corrections.single().index)
            assertEquals("second-stage completion", corrections.single().content)
        }
    }

    @Test
    fun requestUsesTypedInputProtectedFieldsAndOptionalHostedSearch() {
        val assistant = JSONObject().put("role", "assistant").put("content", "check first")
        ResponsesEphemeralState.attachOutputItems(
            assistant,
            JSONArray().put(
                JSONObject()
                    .put("id", "rs_1")
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque"),
            ),
        )
        val messages = JSONArray()
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", "view the image"))
                        .put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:image/png;base64,AA==")),
                        ),
                ),
            )
            .put(assistant)
            .put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", "call_1")
                    .put("content", "{\"ok\":true}"),
            )
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject()
                    .put("name", "device_info")
                    .put("description", "read the device")
                    .put("parameters", JSONObject().put("type", "object")),
            ),
        )

        val request = OpenAiResponsesProvider.buildRequestJson(
            config = config("https://example.com/v1").copy(
                hostedWebSearchEnabled = true,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.HIGH),
                    canDisable = true,
                ),
                reasoningEffort = ReasoningEffort.HIGH,
                extraBodyJson = """{"model":"wrong","store":true,"metadata":{"source":"atom"}}""",
                customBody = listOf(CustomBody("stream", JsonPrimitive(false))),
            ),
            messages = messages,
            tools = tools,
        )

        assertEquals("test-model", request.getString("model"))
        assertEquals("system prompt", request.getString("instructions"))
        assertTrue(request.getBoolean("stream"))
        assertFalse(request.getBoolean("store"))
        assertFalse(request.has("previous_response_id"))
        assertEquals("atom", request.getJSONObject("metadata").getString("source"))
        assertEquals("high", request.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto", request.getJSONObject("reasoning").getString("summary"))
        assertEquals("input_image", request.getJSONArray("input").getJSONObject(0)
            .getJSONArray("content").getJSONObject(1).getString("type"))
        assertEquals("reasoning", request.getJSONArray("input").getJSONObject(1).getString("type"))
        assertEquals("function_call_output", request.getJSONArray("input").getJSONObject(2).getString("type"))
        assertFalse(request.getJSONArray("tools").getJSONObject(0).getBoolean("strict"))
        assertEquals("web_search", request.getJSONArray("tools").getJSONObject(1).getString("type"))
    }

    @Test
    fun requestUsesMergedInstructionsAndTypedDurableHistoryMessages() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "base constraint"))
            .put(JSONObject().put("role", "user").put("content", "old question"))
            .put(JSONObject().put("role", "system").put("content", "compacted context"))
            .put(JSONObject().put("role", "assistant").put("content", "old answer"))
            .put(JSONObject().put("role", "user").put("content", "current question"))

        val request = OpenAiResponsesProvider.buildRequestJson(
            config = config("https://example.com/v1"),
            messages = messages,
            tools = JSONArray(),
        )

        assertEquals("base constraint\n\ncompacted context", request.getString("instructions"))
        val input = request.getJSONArray("input")
        assertEquals(listOf("user", "assistant", "user"), input.roles())
        assertEquals(listOf("message", "message", "message"), input.types())
        assertEquals("old answer", input.getJSONObject(1).getString("content"))
    }

    @Test
    fun completeUsesTerminalOutputAsAuthorityAndEmitsSemanticEvents() {
        val terminalOutput = JSONArray()
            .put(
                JSONObject()
                    .put("id", "rs_1")
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque")
                    .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", "complete summary"))),
            )
            .put(JSONObject().put("id", "ws_1").put("type", "web_search_call").put("status", "completed"))
            .put(
                JSONObject()
                    .put("id", "msg_1")
                    .put("type", "message")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "output_text")
                                .put("text", "Hello world")
                                .put(
                                    "annotations",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "url_citation")
                                            .put("start_index", 2)
                                            .put("end_index", 4)
                                            .put("url", "https://example.com/source")
                                            .put("title", "source"),
                                    ),
                                ),
                        ),
                    ),
            )
            .put(
                JSONObject()
                    .put("id", "fc_1")
                    .put("type", "function_call")
                    .put("call_id", "call_1")
                    .put("name", "device_info")
                    .put("arguments", "{\"detail\":true}"),
            )
        val response = JSONObject()
            .put("status", "completed")
            .put("output", terminalOutput)
            .put(
                "usage",
                JSONObject()
                    .put("input_tokens", 10)
                    .put("output_tokens", 8)
                    .put("total_tokens", 18)
                    .put(
                        "input_tokens_details",
                        JSONObject().put("cached_tokens", 3).put("cache_write_tokens", 4),
                    )
                    .put("output_tokens_details", JSONObject().put("reasoning_tokens", 5)),
            )
        val body = buildString {
            append(event("response.reasoning_summary_text.delta", JSONObject().put("delta", "complete")))
            append(
                event(
                    "response.output_item.added",
                    JSONObject().put("item", JSONObject().put("id", "ws_1").put("type", "web_search_call")),
                ),
            )
            append(
                event(
                    "response.output_item.done",
                    JSONObject().put(
                        "item",
                        JSONObject().put("id", "ws_1").put("type", "web_search_call").put("status", "completed"),
                    ),
                ),
            )
            append(event("response.output_text.delta", JSONObject().put("delta", "hello")))
            append(event("response.completed", JSONObject().put("response", response)))
        }
        val requestBody = AtomicReference<String>()

        withSseServer(body, onRequest = requestBody::set) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                request = ProviderRequest(
                    config = config(baseUrl),
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                    tools = JSONArray(),
                ),
                runController = AgentRunController(),
                onEvent = events::add,
            )

            assertTrue(requestBody.get().contains("\"store\":false"))
            assertEquals("complete summary", result.assistantMessage.getString("reasoning_content"))
            assertTrue(result.assistantMessage.getString("content").contains("[[1]]"))
            assertEquals("call_1", result.assistantMessage.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
            assertNotNull(ResponsesEphemeralState.outputItems(result.assistantMessage))
            assertEquals(1, events.filterIsInstance<ProviderEvent.HostedToolStarted>().size)
            assertEquals(1, events.filterIsInstance<ProviderEvent.HostedToolFinished>().size)
            val usage = events.filterIsInstance<ProviderEvent.Usage>().single().usage
            assertEquals(18, usage.contextTokens)
            assertEquals(3, usage.cachedTokens)
            assertEquals(4, usage.cacheWriteTokens)
            assertEquals(5, usage.reasoningTokens)
        }
    }

    @Test
    fun completeUsesStreamedTextWhenCompletedOutputIsEmpty() {
        val body = buildString {
            append(event("response.reasoning_summary_text.delta", JSONObject().put("delta", "brief analysis")))
            append(event("response.output_text.delta", JSONObject().put("delta", "hello")))
            append(event("response.output_text.delta", JSONObject().put("delta", "world")))
            append(
                event(
                    "response.completed",
                    JSONObject().put(
                        "response",
                        JSONObject().put("status", "completed"),
                    ),
                ),
            )
        }

        withSseServer(body) { baseUrl ->
            val result = OpenAiResponsesProvider.complete(
                ProviderRequest(
                    config(baseUrl),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                    JSONArray(),
                ),
                AgentRunController(),
            )

            assertEquals("Hello world", result.assistantMessage.getString("content"))
            assertEquals("brief analysis", result.assistantMessage.getString("reasoning_content"))
            assertEquals("stop", result.assistantMessage.getString("finish_reason"))
            assertNull(ResponsesEphemeralState.outputItems(result.assistantMessage))
        }
    }

    @Test
    fun completePreservesInterleavedResponseItemOrderAndBlockIdentity() {
        val terminalOutput = JSONArray()
            .put(reasoningItem("rs_1", "determine first"))
            .put(messageItem("msg_1", "Let me check first."))
            .put(JSONObject().put("id", "ws_1").put("type", "web_search_call").put("status", "completed"))
            .put(reasoningItem("rs_2", "organize the result"))
            .put(messageItem("msg_2", "This is the final answer."))
        val body = buildString {
            append(responseTextEvent("response.reasoning_summary_text.delta", "rs_1", 0, "delta", "determine first"))
            append(responseTextEvent("response.reasoning_summary_text.done", "rs_1", 0, "text", "determine first"))
            append(responseTextEvent("response.output_text.delta", "msg_1", 1, "delta", "Let me check first."))
            append(responseTextEvent("response.output_text.done", "msg_1", 1, "text", "Let me check first."))
            append(
                event(
                    "response.output_item.added",
                    JSONObject()
                        .put("output_index", 2)
                        .put("item", JSONObject().put("id", "ws_1").put("type", "web_search_call")),
                ),
            )
            append(
                event(
                    "response.output_item.done",
                    JSONObject()
                        .put("output_index", 2)
                        .put(
                            "item",
                            JSONObject()
                                .put("id", "ws_1")
                                .put("type", "web_search_call")
                                .put("status", "completed"),
                        ),
                ),
            )
            append(responseTextEvent("response.reasoning_summary_text.delta", "rs_2", 3, "delta", "organize the result"))
            append(responseTextEvent("response.reasoning_summary_text.done", "rs_2", 3, "text", "organize the result"))
            append(responseTextEvent("response.output_text.delta", "msg_2", 4, "delta", "This is the final answer."))
            append(responseTextEvent("response.output_text.done", "msg_2", 4, "text", "This is the final answer."))
            append(
                event(
                    "response.completed",
                    JSONObject().put(
                        "response",
                        JSONObject().put("status", "completed").put("output", terminalOutput),
                    ),
                ),
            )
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val result = OpenAiResponsesProvider.complete(
                ProviderRequest(
                    config(baseUrl),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "search")),
                    JSONArray(),
                ),
                AgentRunController(),
                events::add,
            )

            assertEquals("Let me check first. This is the final answer.", result.assistantMessage.getString("content"))
            assertEquals(
                listOf(
                    "start:THINKING:0",
                    "delta:THINKING:0:determine first",
                    "end:THINKING:0",
                    "start:TEXT:1",
                    "delta:TEXT:1:Let me check first.",
                    "end:TEXT:1",
                    "hosted-start:ws_1",
                    "hosted-end:ws_1",
                    "start:THINKING:2",
                    "delta:THINKING:2:organize the result",
                    "end:THINKING:2",
                    "start:TEXT:3",
                    "delta:TEXT:3:This is the final answer.",
                    "end:TEXT:3",
                ),
                events.mapNotNull(::timelineLabel),
            )
        }
    }

    @Test
    fun completeUsesStreamedToolCallWhenCompletedOutputIsEmpty() {
        val body = buildString {
            append(
                event(
                    "response.output_item.added",
                    JSONObject()
                        .put("output_index", 0)
                        .put(
                            "item",
                            JSONObject()
                                .put("id", "fc_1")
                                .put("type", "function_call")
                                .put("call_id", "call_1")
                                .put("name", "device_info"),
                        ),
                ),
            )
            append(
                event(
                    "response.function_call_arguments.delta",
                    JSONObject().put("item_id", "fc_1").put("delta", "{\"detail\":true}"),
                ),
            )
            append(
                event(
                    "response.completed",
                    JSONObject().put(
                        "response",
                        JSONObject().put("status", "completed").put("output", JSONArray()),
                    ),
                ),
            )
        }

        withSseServer(body) { baseUrl ->
            val result = OpenAiResponsesProvider.complete(
                ProviderRequest(
                    config(baseUrl),
                    JSONArray().put(JSONObject().put("role", "user").put("content", "read the device")),
                    JSONArray(),
                ),
                AgentRunController(),
            )

            val call = result.assistantMessage.getJSONArray("tool_calls").getJSONObject(0)
            assertEquals("call_1", call.getString("id"))
            assertEquals("device_info", call.getJSONObject("function").getString("name"))
            assertEquals("{\"detail\":true}", call.getJSONObject("function").getString("arguments"))
            assertEquals("tool_calls", result.assistantMessage.getString("finish_reason"))
            assertNull(ResponsesEphemeralState.outputItems(result.assistantMessage))
        }
    }

    @Test
    fun completeRejectsEofWithoutTerminalEvent() {
        withSseServer(event("response.output_text.delta", JSONObject().put("delta", "partial"))) { baseUrl ->
            val thrown = runCatching {
                OpenAiResponsesProvider.complete(
                    ProviderRequest(
                        config(baseUrl),
                        JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                        JSONArray(),
                    ),
                    AgentRunController(),
                )
            }.exceptionOrNull()
            assertTrue(thrown?.message.orEmpty().contains("missing a legal termination event"))
        }
    }

    @Test
    fun citationsDeduplicateAndFallBackForInvalidOffsets() {
        val formatted = CitationFormatter.apply(
            "Chinese answer",
            listOf(
                CitationAnnotation(0, 2, "https://example.com/a", "A"),
                CitationAnnotation(0, 2, "https://example.com/a", "duplicate"),
                CitationAnnotation(99, 120, "https://example.com/b", "B"),
            ),
        )
        assertEquals(1, "https://example.com/a".toRegex().findAll(formatted).count())
        assertTrue(formatted.contains("[[1]]"))
        assertTrue(formatted.contains("Source: "))
        assertTrue(formatted.contains("https://example.com/b"))
    }

    private fun config(baseUrl: String) = AgentModelClient.ModelConfig(
        providerSourceType = "custom",
        baseUrl = baseUrl,
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = "system prompt",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES,
    )

    private fun event(type: String, fields: JSONObject): String {
        val event = JSONObject(fields.toString()).put("type", type)
        return "event: $type\ndata: $event\n\n"
    }

    private fun responseTextEvent(
        type: String,
        itemId: String,
        outputIndex: Int,
        valueKey: String,
        value: String,
    ): String = event(
        type,
        JSONObject()
            .put("item_id", itemId)
            .put("output_index", outputIndex)
            .put("summary_index", 0)
            .put("content_index", 0)
            .put(valueKey, value),
    )

    private fun reasoningItem(id: String, text: String): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "reasoning")
        .put("summary", JSONArray().put(JSONObject().put("text", text)))

    private fun messageItem(id: String, text: String): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "message")
        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))

    private fun timelineLabel(event: ProviderEvent): String? = when (event) {
        is ProviderEvent.BlockStart -> "start:${event.kind}:${event.index}"
        is ProviderEvent.BlockDelta -> "delta:${event.kind}:${event.index}:${event.delta}"
        is ProviderEvent.BlockEnd -> "end:${event.kind}:${event.index}"
        is ProviderEvent.HostedToolStarted -> "hosted-start:${event.id}"
        is ProviderEvent.HostedToolFinished -> "hosted-end:${event.id}"
        else -> null
    }

    private fun withSseServer(
        body: String,
        onRequest: (String) -> Unit = {},
        writeBody: ((OutputStream) -> Unit)? = null,
        block: (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/responses") { exchange ->
            onRequest(exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) })
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

    private fun JSONArray.types(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("type") }
}
