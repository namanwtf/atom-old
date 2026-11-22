package io.github.namanwtf.atom.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.namanwtf.atom.agent.roleplay.CharacterCardCodec
import io.github.namanwtf.atom.agent.roleplay.RoleplayRunContext
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.data.model.CustomBody
import io.github.namanwtf.atom.data.model.OpenAiEndpointMode
import io.github.namanwtf.atom.data.model.ProviderTypes
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCompactionProviderTest {
    @Test
    fun compactionKeepsToolsAndProtectsProtocolFieldsAcrossAllProtocols() {
        listOf(
            OpenAiChatCompletionsProvider,
            OpenAiResponsesProvider,
            AnthropicMessagesProvider,
            GeminiInteractionsProvider,
        ).forEach { provider ->
            val captured = AtomicReference<JSONObject>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                captured.set(JSONObject(exchange.requestBody.bufferedReader().readText()))
                val body = """{"error":{"code":"fixture_rejected"}}""".toByteArray()
                exchange.sendResponseHeaders(400, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val baseUrl = if (provider === GeminiInteractionsProvider) {
                    "http://127.0.0.1:${server.address.port}/v1beta"
                } else {
                    "http://127.0.0.1:${server.address.port}"
                }
                val config = AgentModelClient.ModelConfig(
                    providerType = when (provider) {
                        AnthropicMessagesProvider -> ProviderTypes.ANTHROPIC
                        GeminiInteractionsProvider -> ProviderTypes.GEMINI
                        else -> ProviderTypes.OPENAI_COMPATIBLE
                    },
                    baseUrl = baseUrl,
                    apiKey = "fixture",
                    model = "fixture",
                    reasoningEffort = io.github.namanwtf.atom.data.model.ReasoningEffort.DEFAULT,
                    openAiEndpointMode = if (provider === OpenAiResponsesProvider) {
                        OpenAiEndpointMode.RESPONSES
                    } else {
                        OpenAiEndpointMode.CHAT_COMPLETIONS
                    },
                    systemPrompt = "summary rule",
                    hostedWebSearchEnabled = provider === OpenAiResponsesProvider,
                    extraBodyJson = """{"tools":[{"type":"web_search"}],"input":"BAD_INPUT","temperature":0.2}""",
                    customBody = listOf(
                        CustomBody("messages", JsonPrimitive("BAD_MESSAGES")),
                        CustomBody("tool_choice", JsonPrimitive("required")),
                    ),
                )
                val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
                val request = ProviderRequest(
                    config = config,
                    messages = JSONArray().put(AgentConversationCodec.userTextMessage("summarize the following history")),
                    tools = tools,
                    purpose = ProviderRequestPurpose.COMPACTION,
                )
                assertThrows(AgentModelFailure::class.java) {
                    provider.complete(request, AgentRunController())
                }
                val body = captured.get()
                assertNotNull(body)
                assertTrue(body.getJSONArray("tools").length() > 0)
                when (provider) {
                    OpenAiChatCompletionsProvider,
                    OpenAiResponsesProvider,
                    -> {
                        assertEquals("none", body.getString("tool_choice"))
                        assertEquals(0.2, body.getDouble("temperature"), 0.0001)
                    }
                    AnthropicMessagesProvider -> {
                        // Anthropic keeps the original tool_choice to reuse the messages cache and shields protected protocol fields from being overwritten.
                        assertFalse(body.has("tool_choice"))
                    }
                    GeminiInteractionsProvider -> {
                        assertEquals("none", body.getJSONObject("generation_config").getString("tool_choice"))
                        assertEquals(0.2, body.getDouble("temperature"), 0.0001)
                    }
                }
                assertFalse(body.toString().contains("BAD_INPUT"))
                assertFalse(body.toString().contains("BAD_MESSAGES"))
                assertTrue(body.toString().contains("summarize the following history"))
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun allProtocolsDisableToolsAndIgnoreCustomInputOverridesForReplyRewrites() {
        assertIsolatedRequests(ProviderRequestPurpose.REPLY_REWRITE)
    }

    @Test
    fun anthropicPreservesDialogueDepthAndCollectsSystemDepth() {
        val captured = AtomicReference<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured.set(JSONObject(exchange.requestBody.bufferedReader().readText()))
            val body = "{\"error\":{\"code\":\"fixture_rejected\"}}".toByteArray()
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            listOf("user", "assistant", "system").forEach { role ->
                val card = CharacterCardCodec.decodeJson("""{"name":"Lin Zhou","extensions":{"depth_prompt":{
                    "prompt":"depth setting","depth":1,"role":"$role"}}}""")
                val context = RoleplayRunContext("fixture", card, "user", "", contextWindow = 128_000)
                val source = JSONArray().put(context.personaMessage())
                    .put(AgentConversationCodec.userTextMessage("first sentence").put("_atom_message_id", "first"))
                    .put(JSONObject().put("role", "assistant").put("content", "second sentence"))
                val config = AgentModelClient.ModelConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "fixture",
                    model = "fixture", systemPrompt = "",
                )
                assertThrows(AgentModelFailure::class.java) {
                    AnthropicMessagesProvider.complete(ProviderRequest(config, context.projectMessages(source), JSONArray()), AgentRunController())
                }
                val body = captured.get()
                assertFalse(body.toString().contains("_atom_message_id"))
                if (role == "system") assertTrue(body.getJSONArray("system").getJSONObject(0).getString("text").contains("depth setting")) else {
                    val text = body.getJSONArray("messages").toString()
                    assertTrue(text.indexOf("first sentence") < text.indexOf("depth setting"))
                    assertTrue(text.indexOf("depth setting") < text.indexOf("second sentence"))
                }
            }
        } finally {
            server.stop(0)
        }
    }

    private fun assertIsolatedRequests(purpose: ProviderRequestPurpose) {
        listOf(OpenAiChatCompletionsProvider, OpenAiResponsesProvider, AnthropicMessagesProvider).forEach { provider ->
            val captured = AtomicReference<JSONObject>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                captured.set(JSONObject(exchange.requestBody.bufferedReader().readText()))
                val body = """{"error":{"code":"fixture_rejected"}}""".toByteArray()
                exchange.sendResponseHeaders(400, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val config = AgentModelClient.ModelConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}", apiKey = "fixture",
                    model = "fixture", openAiEndpointMode = if (provider === OpenAiResponsesProvider) OpenAiEndpointMode.RESPONSES
                        else OpenAiEndpointMode.CHAT_COMPLETIONS, systemPrompt = "summary rule", hostedWebSearchEnabled = true,
                    extraBodyJson = """{"tools":[{"type":"web_search"}],"input":"BAD_INPUT"}""",
                    customBody = listOf(CustomBody("messages", JsonPrimitive("BAD_MESSAGES"))),
                )
                val request = ProviderRequest(config,
                    JSONArray().put(AgentConversationCodec.userTextMessage("summarize the following history")),
                    AgentToolCatalog.build(terminalTools = false, browserTools = false), purpose = purpose)
                assertThrows(AgentModelFailure::class.java) {
                    provider.complete(request, AgentRunController())
                }
                val body = captured.get()
                assertNotNull(body)
                assertFalse(body.has("tools"))
                assertFalse(body.has("tool_choice"))
                assertFalse(body.toString().contains("BAD_INPUT"))
                assertFalse(body.toString().contains("BAD_MESSAGES"))
                assertTrue(body.toString().contains("summarize the following history"))
            } finally {
                server.stop(0)
            }
        }
    }
}
