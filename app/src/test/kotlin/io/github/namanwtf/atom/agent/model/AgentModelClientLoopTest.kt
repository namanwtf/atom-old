package io.github.namanwtf.atom.agent.model

import io.github.namanwtf.atom.agent.runtime.AgentEvent
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.agent.tool.AgentToolCapabilities
import io.github.namanwtf.atom.data.model.OpenAiEndpointMode
import io.github.namanwtf.atom.data.model.ProviderTypes
import java.util.concurrent.atomic.AtomicInteger
import io.github.namanwtf.atom.agent.device.RootAccessState
import io.github.namanwtf.atom.agent.device.RootAccessStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelClientLoopTest {
    @Test
    fun rootProbeStatesKeepThePrefixStableUntilGrantActuallyChanges() {
        var root = RootAccessState(isChecking = true)
        val calls = mutableListOf<String>()
        val systems = mutableListOf<String>()
        val toolLists = mutableListOf<String>()
        fun record(request: ProviderRequest) {
            systems += (0 until request.messages.length()).map(request.messages::getJSONObject)
                .filter { it.optString("role") == "system" }.joinToString { it.toString() }
            toolLists += request.tools.toString()
        }
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                record(request)
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("context", "get_current_context", "{}")))
            },
            { request, _ ->
                record(request)
                assertFalse(request.tools.toString().contains("set_setting"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("probe", "terminal", """{"action":"check_root"}""")))
            },
            { request, _ ->
                record(request)
                assertTrue(request.messages.toString().contains("Root authorization confirmed"))
                assertTrue(request.tools.toString().contains("set_setting"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("root-command", "terminal", """{"command":"id -u","identity":"root"}""")))
            },
            { _, _ -> assistant(content = "verified Root", finishReason = "stop") },
        ))
        AgentModelClient.complete(
            modelConfig().copy(terminalTools = true, deviceSensitiveActionTools = true), "check Root",
            AgentModelClient.ToolExecutor { call ->
                calls += call.id
                root = if (call.id == "context") RootAccessState(RootAccessStatus.TIMED_OUT, suPresent = true)
                    else RootAccessState(RootAccessStatus.GRANTED, suPresent = true)
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
            provider = provider,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = root.isGranted, rootState = root) },
        )
        assertEquals(listOf("context", "probe", "root-command"), calls)
        // From checking through timeout, if unauthenticated: the system prompt and tool catalog stay byte-for-byte identical; they only update as a whole after authorization is confirmed.
        assertEquals(systems[0], systems[1])
        assertEquals(toolLists[0], toolLists[1])
        assertNotEquals(systems[1], systems[2])
    }

    @Test
    fun transientCapabilityLossDoesNotShrinkTheToolCatalogMidRun() {
        var accessibility = true
        val toolLists = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                toolLists += request.tools.toString()
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("context", "get_current_context", "{}")))
            },
            { request, _ ->
                toolLists += request.tools.toString()
                assistant(content = "done", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            modelConfig(), "take a look at the screen",
            AgentModelClient.ToolExecutor {
                accessibility = false
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
            provider = provider,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false, accessibilityAvailable = accessibility) },
        )
        assertTrue(toolLists[0].contains("observe_screen"))
        assertEquals(toolLists[0], toolLists[1])
    }

    @Test
    fun capabilityLostMidRunStaysListedButExecutionReportsTheCatalogCode() {
        var notifications = true
        val executed = mutableListOf<String>()
        val results = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertTrue(request.tools.toString().contains("recent_notifications"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("ctx", "get_current_context", "{}")))
            },
            { request, _ ->
                assertTrue(request.tools.toString().contains("recent_notifications"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("notes", "recent_notifications", "{}")))
            },
            { request, _ ->
                results += request.messages.toString()
                assistant(content = "done", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            modelConfig().copy(deviceSensitiveReadTools = true), "look at the notifications",
            AgentModelClient.ToolExecutor { call ->
                executed += call.id
                notifications = false
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
            provider = provider,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false, notificationsAllowed = notifications) },
        )
        assertEquals(listOf("ctx"), executed)
        assertTrue(results.single().contains("NOTIFICATION_ACCESS_REQUIRED"))
    }

    @Test
    fun hostedSearchReplacesOnlyTheLocalSearchInSupportedConfigurations() {
        for (providerType in listOf(ProviderTypes.OPENAI_COMPATIBLE, ProviderTypes.ANTHROPIC)) {
            for (endpoint in listOf(OpenAiEndpointMode.RESPONSES, OpenAiEndpointMode.CHAT_COMPLETIONS)) {
                for (hosted in listOf(false, true)) for (browser in listOf(false, true)) {
                    val effectiveHosted = providerType == ProviderTypes.OPENAI_COMPATIBLE &&
                        endpoint == OpenAiEndpointMode.RESPONSES && hosted
                    val config = modelConfig().copy(
                        providerType = providerType, openAiEndpointMode = endpoint,
                        hostedWebSearchEnabled = hosted, browserTools = browser,
                    )
                    val provider = ScriptedProvider(listOf({ request, _ ->
                        val names = (0 until request.tools.length()).map {
                            request.tools.getJSONObject(it).getJSONObject("function").getString("name")
                        }
                        assertEquals(browser && !effectiveHosted, "web_search" in names)
                        assertEquals(browser, "fetch_url" in names)
                        assertEquals(browser, "browser_use" in names)
                        assertEquals(effectiveHosted, request.messages.toString().contains("Provider hosted web_search"))
                        assertEquals(browser && !effectiveHosted, request.messages.toString().contains("use the local web_search"))
                        if (effectiveHosted) {
                            val outgoing = ResponsesRequestBuilder.build(config, request.messages, request.tools).getJSONArray("tools")
                            assertEquals(1, (0 until outgoing.length()).count { outgoing.getJSONObject(it).optString("type") == "web_search" })
                            assertFalse((0 until outgoing.length()).any { outgoing.getJSONObject(it).optString("name") == "web_search" })
                        }
                        assistant(content = "done", finishReason = "stop")
                    }))
                    AgentModelClient.complete(config, "look up public information", AgentModelClient.ToolExecutor { error("must not execute local requests") }, provider = provider)
                }
            }
        }
    }

    @Test
    fun hiddenLocalSearchCannotBeExecutedAsAFunctionDuringHostedSearch() {
        val provider = ScriptedProvider(listOf(
            { _, _ -> assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("local-search", "web_search", """{"query":"example"}"""))) },
            { request, _ ->
                assertTrue(request.messages.toString().contains("TOOL_NOT_AVAILABLE"))
                assistant(content = "used the available capability", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            config = modelConfig().copy(openAiEndpointMode = OpenAiEndpointMode.RESPONSES, hostedWebSearchEnabled = true, browserTools = true),
            prompt = "look up public information", provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { error("hosted search must not accidentally enter the local executor") },
        )
    }

    @Test
    fun eachRoundUsesOneCapabilitySnapshotForToolAvailabilityAndPrompt() {
        var root = true
        var captures = 0
        val executed = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertTrue(request.tools.toString().contains("set_setting"))
                assertTrue(request.messages.toString().contains("relevant app private files and databases"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("first", "get_current_context", "{}")))
            },
            { request, _ ->
                assertFalse(request.tools.toString().contains("set_setting"))
                assertFalse(request.messages.toString().contains("relevant app private files and databases"))
                assertTrue(request.messages.toString().contains("identity=user"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("stale", "set_setting", "{}"),
                ))
            },
            { request, _ ->
                assertTrue(request.messages.toString().contains("TOOL_NOT_AVAILABLE"))
                assistant(content = "done", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true, deviceSensitiveActionTools = true),
            prompt = "start",
            provider = provider,
            capabilitiesProvider = {
                captures++
                AgentToolCapabilities(rootAvailable = root)
            },
            toolExecutor = AgentModelClient.ToolExecutor {
                executed += it.id
                root = false
                AgentModelClient.ToolResult("{\"ok\":true}")
            },
        )
        assertEquals(listOf("first"), executed)
        // One catalog snapshot per turn, plus one pre-execution re-check for each directory tool actually executed.
        assertEquals(5, captures)
    }

    @Test
    fun signedAnthropicToolRoundKeepsTheOriginalToolAndSystemDeclarations() {
        var root = true
        var captures = 0
        val firstAssistant = assistant(
            finishReason = "tool_calls",
            toolCalls = listOf(toolCall("signed-call", "get_current_context", "{}")),
        ).also { response ->
            AnthropicEphemeralState.attachContentBlocks(response, JSONArray()
                .put(JSONObject().put("type", "thinking").put("thinking", "")
                    .put("signature", "signed-prefix"))
                .put(JSONObject().put("type", "tool_use").put("id", "signed-call")
                    .put("name", "get_current_context").put("input", JSONObject())))
        }
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertTrue(request.tools.toString().contains("set_setting"))
                assertTrue(request.messages.toString().contains("relevant app private files and databases"))
                firstAssistant
            },
            { request, _ ->
                assertTrue(request.tools.toString().contains("set_setting"))
                assertTrue(request.messages.toString().contains("relevant app private files and databases"))
                assistant(content = "done", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true, deviceSensitiveActionTools = true),
            prompt = "start",
            provider = provider,
            capabilitiesProvider = {
                captures++
                AgentToolCapabilities(rootAvailable = root)
            },
            toolExecutor = AgentModelClient.ToolExecutor {
                root = false
                AgentModelClient.ToolResult("tool result")
            },
        )

        // The signed turn reuses the previous turn's catalog without re-snapshotting; the other comes from the availability re-check before tool execution.
        assertEquals(3, captures)
    }

    @Test
    fun textOnlyRunReturnsIncrementalTranscript() {
        val provider = ScriptedProvider(
            assistant(content = "done", finishReason = "stop")
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "current question",
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "old question"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "old answer"),
            ),
            toolExecutor = AgentModelClient.ToolExecutor { error("must not call a tool") },
            provider = provider,
        )

        assertEquals("done", result.content)
        assertEquals(listOf("assistant"), result.transcript.map { it.role })
        assertEquals("done", result.transcript.single().content)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun toolBatchFeedsResultsBackInSourceOrder() {
        val provider = ScriptedProvider(
            assistant(
                content = "execute first",
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "get_current_context", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
                reasoning = "need two results",
            ),
            assistant(content = "completed", finishReason = "stop"),
        )
        val executed = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "start",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                AgentModelClient.ToolResult(
                    JSONObject()
                        .put("ok", true)
                        .put("call", call.id)
                        .toString()
                )
            },
            provider = provider,
        )

        assertEquals(listOf("call-1", "call-2"), executed)
        assertEquals("need two results", result.reasoningContent)
        assertEquals(
            listOf("assistant", "tool", "tool", "assistant"),
            result.transcript.map { it.role },
        )
        assertEquals(
            listOf("assistant", "tool", "tool"),
            provider.requests[1].roleSuffix(3),
        )
        assertEquals("call-1", provider.requests[1].getJSONObjectFromEnd(2).getString("tool_call_id"))
        assertEquals("call-2", provider.requests[1].getJSONObjectFromEnd(1).getString("tool_call_id"))
    }

    @Test
    fun steeringWaitsForWholeToolBatchWithoutCancellingResources() {
        val controller = AgentRunController()
        val cancelledResources = AtomicInteger(0)
        controller.register { cancelledResources.incrementAndGet() }
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "get_current_context", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
            ),
            assistant(content = "completed per the supplement", finishReason = "stop"),
        )
        val executed = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "start",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                if (call.id == "call-1") controller.steer("switch to the second approach")
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            provider = provider,
            runController = controller,
        )

        assertEquals(listOf("call-1", "call-2"), executed)
        assertEquals(0, cancelledResources.get())
        assertFalse(controller.hasPendingSteering)
        assertEquals("completed per the supplement", result.content)
        assertEquals(
            listOf("assistant", "tool", "tool", "user"),
            provider.requests[1].roleSuffix(4),
        )
        assertTrue(
            provider.requests[1]
                .getJSONObjectFromEnd(1)
                .getString("content")
                .contains("switch to the second approach")
        )
    }

    @Test
    fun steeringAfterTextResponsePreservesThatAssistantTurn() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    controller.steer("add one more item")
                    assistant(content = "first-part answer", finishReason = "stop")
                },
                { _, _ -> assistant(content = "final answer", finishReason = "stop") },
            )
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "start",
            toolExecutor = AgentModelClient.ToolExecutor { error("must not call a tool") },
            provider = provider,
            runController = controller,
        )

        assertEquals("final answer", result.content)
        assertEquals(
            listOf("assistant", "user", "assistant"),
            result.transcript.map { it.role },
        )
        assertEquals("first-part answer", result.transcript.first().content)
    }

    @Test
    fun truncatedToolCallIsReportedWithoutExecution() {
        listOf("length", "max_tokens").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "terminal", "{\"command\":\"rm -")),
                ),
                assistant(content = "replanned", finishReason = "stop"),
            )
            var executed = false
            val events = mutableListOf<AgentEvent>()

            val result = AgentModelClient.complete(
                config = modelConfig(),
                prompt = "execute the task",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executed = true
                    AgentModelClient.ToolResult("unexpected")
                },
                provider = provider,
                onEvent = events::add,
            )

            assertFalse(executed)
            assertEquals("replanned", result.content)
            val toolResult = provider.requests[1].getJSONObjectFromEnd(1)
            assertEquals("tool", toolResult.getString("role"))
            assertTrue(toolResult.getString("content").contains("TRUNCATED_TOOL_CALL"))
            val toolStarted = events.filterIsInstance<AgentEvent.ToolStarted>().single()
            assertFalse(toolStarted.argsPreview.contains("rm -"))
        }
    }

    @Test
    fun malformedAndDuplicateToolCallsReceiveStableTerminalResults() {
        val malformedCalls = JSONArray()
            .put(toolCall("duplicate", "get_current_context", "{}"))
            .put(toolCall("duplicate", "get_current_context", "{}"))
            .put("not-an-object")
        val firstResponse = assistant(content = "", finishReason = "tool_calls")
            .put("tool_calls", malformedCalls)
        val provider = ScriptedProvider(
            firstResponse,
            assistant(content = "recovered", finishReason = "stop"),
        )
        val executed = mutableListOf<Pair<String, String>>()

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "start",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id to call.name
                AgentModelClient.ToolResult(JSONObject().put("ok", false).toString())
            },
            provider = provider,
        )

        assertEquals(
            listOf(
                "duplicate" to "get_current_context",
                "duplicate_1" to "get_current_context",
            ),
            executed,
        )
        val secondRequest = provider.requests[1]
        assertEquals(
            listOf("duplicate", "duplicate_1", "tool_call_2"),
            secondRequest
                .getJSONObject(secondRequest.length() - 4)
                .getJSONArray("tool_calls")
                .let { calls -> (0 until calls.length()).map { calls.getJSONObject(it).getString("id") } },
        )
        assertEquals(
            listOf("duplicate", "duplicate_1", "tool_call_2"),
            (3 downTo 1).map { offset ->
                secondRequest.getJSONObjectFromEnd(offset).getString("tool_call_id")
            },
        )
    }

    @Test
    fun imageObservationsFollowEveryToolResultInTheBatch() {
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "observe_screen", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
            ),
            assistant(content = "saw", finishReason = "stop"),
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "observe",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = if (call.id == "call-1") {
                        listOf(
                            AgentModelClient.ModelImage(
                                reference = "data:image/png;base64,AA==",
                                mimeType = "image/png",
                                bytes = 1,
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            provider = provider,
        )

        assertEquals(
            listOf("assistant", "tool", "tool", "user"),
            provider.requests[1].roleSuffix(4),
        )
        assertFalse(result.transcript.any { it.contentJson.contains("base64") })
        assertFalse(result.transcript.any { it.contentJson.contains("not persisted to session") })
    }

    @Test
    fun toolScreenshotIsConsumedByExactlyOneModelRequest() {
        val screenshot = "data:image/png;base64,c2NyZWVu"
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe", "observe_screen", "{}")),
            ),
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("tap", "tap", "{\"x\":10,\"y\":20}")),
            ),
            assistant(content = "done", finishReason = "stop"),
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "observe then tap",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = if (call.name == "observe_screen") {
                        listOf(
                            AgentModelClient.ModelImage(
                                reference = screenshot,
                                mimeType = "image/png",
                                bytes = 6,
                                source = "screen",
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            provider = provider,
        )

        assertFalse(provider.requests[0].toString().contains(screenshot))
        assertTrue(provider.requests[1].toString().contains(screenshot))
        assertFalse(provider.requests[2].toString().contains(screenshot))
        assertFalse(result.transcript.any { it.contentJson.contains(screenshot) })
    }

    @Test
    fun newerToolImageReplacesThePreviousTransientObservation() {
        val firstImage = "data:image/png;base64,Zmlyc3Q="
        val secondImage = "data:image/png;base64,c2Vjb25k"
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe-1", "observe_screen", "{}")),
            ),
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe-2", "observe_screen", "{}")),
            ),
            assistant(content = "done", finishReason = "stop"),
        )
        var observationIndex = 0

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "continuous observation",
            toolExecutor = AgentModelClient.ToolExecutor {
                val reference = if (observationIndex++ == 0) firstImage else secondImage
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = listOf(
                        AgentModelClient.ModelImage(
                            reference = reference,
                            mimeType = "image/png",
                            bytes = 6,
                            source = "screen",
                        )
                    ),
                )
            },
            provider = provider,
        )

        assertTrue(provider.requests[1].toString().contains(firstImage))
        assertFalse(provider.requests[2].toString().contains(firstImage))
        assertTrue(provider.requests[2].toString().contains(secondImage))
    }

    @Test
    fun toolArgumentsReachExecutorWithoutSchemaPreflight() {
        val calls = listOf(
            toolCall("write", "write_file", """{"path":"note.md","content":"content","identity":"USER","extra":true}"""),
            toolCall("read", "read_file", """{"path":"note.md","start_line":1,"offset_bytes":0,"max_bytes":1000000}"""),
            toolCall("missing", "tap", "{}"),
        )
        val provider = ScriptedProvider(
            assistant(finishReason = "tool_calls", toolCalls = calls),
            assistant(content = "done", finishReason = "stop"),
        )
        val executed = mutableListOf<AgentModelClient.ToolCall>()
        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true),
            prompt = "execute the tool",
            toolExecutor = AgentModelClient.ToolExecutor {
                executed += it
                AgentModelClient.ToolResult(
                    if (it.id == "missing") """{"ok":false,"code":"INVALID_ARGUMENT","message":"missing coordinates"}"""
                    else """{"ok":true}""",
                )
            },
            provider = provider,
        )
        assertEquals(calls.map { call ->
            val function = call.getJSONObject("function")
            AgentModelClient.ToolCall(call.getString("id"), function.getString("name"), function.getString("arguments"))
        }, executed)
        val messages = provider.requests[1].toString()
        assertTrue(messages.contains("missing coordinates"))
        assertFalse(messages.contains("INVALID_TOOL_ARGUMENTS"))
    }

    @Test
    fun remoteToolSchemasAreNotInterpretedByTheLoop() {
        val call = toolCall("remote", "mcp_test_write", """{"content":"body","extra":true}""")
        val provider = ScriptedProvider(
            assistant(finishReason = "tool_calls", toolCalls = listOf(call)),
            assistant(content = "done", finishReason = "stop"),
        )
        val executed = mutableListOf<AgentModelClient.ToolCall>()
        AgentModelClient.complete(
            config = modelConfig(), prompt = "write a file", provider = provider,
            additionalTools = JSONArray().put(AgentToolSchema.function("mcp_test_write", "remote file tool",
                JSONObject("""{"type":"object","required":["mode"],"additionalProperties":false}"""))),
            toolExecutor = AgentModelClient.ToolExecutor {
                executed += it
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )
        assertEquals(listOf(AgentModelClient.ToolCall("remote", "mcp_test_write", """{"content":"body","extra":true}""")), executed)
    }

    @Test
    fun contradictoryStopReasonNeverExecutesToolCalls() {
        listOf("stop").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "tap", "{\"x\":1,\"y\":2}")),
                ),
                assistant(content = "ended safely", finishReason = "stop"),
            )
            var executed = false

            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "start",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executed = true
                    AgentModelClient.ToolResult("unexpected")
                },
                provider = provider,
            )

            assertFalse(executed)
            assertTrue(
                provider.requests[1]
                    .getJSONObjectFromEnd(1)
                    .getString("content")
                    .contains("UNEXPECTED_TOOL_CALL")
            )
        }
    }

    @Test
    fun refusalEndsRunWithoutReplayingPartialAssistantOrExecutingTools() {
        listOf("content_filter", "refusal").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    content = "half reply",
                    reasoning = "half thinking",
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "tap", "{\"x\":1,\"y\":2}")),
                ).put(
                    "stop_details",
                    JSONObject().put("type", "refusal").put("category", "cyber").put("explanation", "provider explanation text"),
                ),
                assistant(content = "must not request again", finishReason = "stop"),
            )
            var executed = false

            val failure = assertThrows(AgentModelExecutionException::class.java) {
                AgentModelClient.complete(
                    config = modelConfig(),
                    prompt = "start",
                    toolExecutor = AgentModelClient.ToolExecutor {
                        executed = true
                        AgentModelClient.ToolResult("unexpected")
                    },
                    provider = provider,
                )
            }

            assertFalse(executed)
            assertEquals(1, provider.requests.size)
            val cause = failure.cause as AgentModelFailure
            assertEquals("MODEL_CONTENT_FILTER", cause.code)
            assertFalse(cause.retryable)
            assertTrue(cause.message.orEmpty().contains("provider explanation text"))
            assertTrue(failure.transcript.none { it.role == "assistant" })
        }
    }

    @Test
    fun normalToolStopAliasesExecuteValidatedCalls() {
        listOf("tool_calls", "tool_use").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "get_current_context", "{}")),
                ),
                assistant(content = "done", finishReason = "stop"),
            )
            var executions = 0

            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "start",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executions += 1
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                provider = provider,
            )

            assertEquals(1, executions)
        }
    }

    @Test
    fun providerFailureCarriesCompletedToolTranscriptForSafeRecovery() {
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    assistant(
                        finishReason = "tool_calls",
                        reasoning = "check the state first",
                        toolCalls = listOf(
                            toolCall("call-1", "get_current_context", "{}")
                        ),
                    )
                },
                { _, _ -> error("provider disconnected") },
            )
        )

        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "start",
                toolExecutor = AgentModelClient.ToolExecutor {
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                provider = provider,
            )
        }

        assertEquals(listOf("assistant", "tool"), failure.transcript.map { it.role })
        assertEquals("check the state first", failure.reasoningContent)
    }

    @Test
    fun loopContinuesPastFormerLocalLimitsUntilProviderFinishes() {
        val toolRounds = 257
        val responses = List<(ProviderRequest, AgentRunController) -> JSONObject>(toolRounds) { index ->
            { _, _ ->
                assistant(
                    finishReason = "tool_calls",
                    toolCalls = listOf(toolCall("call-$index", "get_current_context", "{}")),
                )
            }
        } + listOf<(ProviderRequest, AgentRunController) -> JSONObject>(
            { _, _ -> assistant(content = "done", finishReason = "stop") }
        )
        val provider = ScriptedProvider(responses)
        var executions = 0
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("start"))

        val result = AgentLoop(
            config = modelConfig(),
            messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executions += 1
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
        ).run()

        assertEquals("done", result.content)
        assertEquals(toolRounds, executions)
        assertEquals(toolRounds + 1, provider.requests.size)
    }

    @Test
    fun retryPreservesToolResultsAndImagesWithoutReplayingToolsOrFailedReasoning() {
        val requests = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        var executions = 0
        val provider = object : AgentProviderClient by ScriptedProvider(emptyList()) {
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                requests += request.messages.toString()
                sessions += request.sessionId
                onEvent(ProviderEvent.RequestStarted)
                return when (requests.size) {
                    1 -> ProviderResponse(assistant(
                        finishReason = "tool_calls",
                        reasoning = "observe first",
                        toolCalls = listOf(toolCall("observe-1", "get_current_context", "{}")),
                    ))
                    2 -> {
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "failed thinking"))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 1, "half answer"))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 2, "half arguments"))
                        throw java.net.SocketTimeoutException("timeout")
                    }
                    else -> ProviderResponse(assistant(content = "done", finishReason = "stop", reasoning = "observation succeeded"))
                }
            }
        }
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("start"))
        val loop = AgentLoop(
            config = modelConfig(), messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executions++
                AgentModelClient.ToolResult(
                    content = "observation result",
                    images = listOf(AgentModelClient.ModelImage("data:image/png;base64,dGVzdA==", "image/png", 4)),
                )
            },
            runController = AgentRunController(), traceFormatter = AgentTraceFormatter(),
            onEvent = events::add, modelRetry = AgentModelRetry { _, _ -> },
            sessionId = "conversation-retry",
        )
        val result = loop.run()
        assertEquals(1, executions)
        assertEquals(3, requests.size)
        assertEquals(List(3) { "conversation-retry" }, sessions)
        assertEquals(requests[1], requests[2])
        assertTrue(requests[2].contains("data:image/png"))
        assertFalse(messages.toString().contains("data:image/png"))
        assertFalse(messages.toString().contains("half"))
        assertEquals("observed first, observed success", result.reasoningContent)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
        assertEquals(2, events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().round)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolStarted>().size)
    }

    private class ScriptedProvider(
        private val responses: List<(ProviderRequest, AgentRunController) -> JSONObject>,
    ) : AgentProviderClient {
        constructor(vararg responses: JSONObject) : this(
            responses.map { response -> { _, _ -> response } }
        )

        override val id: String = "scripted"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        val requests = mutableListOf<JSONArray>()
        private var index = 0

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            requests += JSONArray(request.messages.toString())
            val response = responses.getOrNull(index)
                ?: error("missing scripted response ${index + 1}")
            index += 1
            return ProviderResponse(response(request, runController))
        }
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            contextWindow = 128_000,
            systemPrompt = "",
            browserTools = false,
        )

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
        reasoning: String = "",
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", content)
            .put("reasoning_content", reasoning)
            .put("finish_reason", finishReason)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put("tool_calls", JSONArray(toolCalls))
                }
            }

    private fun toolCall(
        id: String,
        name: String,
        arguments: String,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", arguments),
            )

    private fun JSONArray.roleSuffix(count: Int): List<String> =
        ((length() - count) until length()).map { index ->
            getJSONObject(index).getString("role")
        }

    private fun JSONArray.getJSONObjectFromEnd(offset: Int): JSONObject =
        getJSONObject(length() - offset)

    @Test
    fun steeringMarksInternalFlagAndOpenAiProjectionStripsIt() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    controller.steer("continue checking the log")
                    assistant(content = "first phase", finishReason = "stop")
                },
                { _, _ -> assistant(content = "completed", finishReason = "stop") },
            ),
        )

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "start",
            toolExecutor = AgentModelClient.ToolExecutor { error("must not call a tool") },
            provider = provider,
            runController = controller,
        )

        val steered = provider.requests[1].getJSONObjectFromEnd(1)
        assertEquals("user", steered.getString("role"))
        assertTrue(steered.optBoolean("_atom_steering"))
        val projected = OpenAiRequestMessages.forChatCompletions(provider.requests[1])
        assertFalse(projected.toString().contains("_atom_steering"))
    }
}
