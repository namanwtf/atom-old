package io.github.namanwtf.atom.agent.model

import io.github.namanwtf.atom.agent.memory.AgentMemoryContextBuilder
import io.github.namanwtf.atom.agent.roleplay.CharacterCardCodec
import io.github.namanwtf.atom.agent.roleplay.CharacterMemoryTools
import io.github.namanwtf.atom.agent.roleplay.RoleplayRunContext
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRoleplayRuntimeTest {
    @Test
    fun continuationPromptAndLaterSteeringKeepTheirUiSupplementIdentifiers() {
        val controller = AgentRunController()
        controller.steer("the later supplement")
        val provider = provider { request ->
            val users = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                .filter { it.optString("role") == "user" }
            assertEquals(listOf("user-next-supplement-7", "user-next-supplement-8"),
                users.map { it.getString("_atom_message_id") })
            reply("supplement completed")
        }
        val result = AgentModelClient.complete(
            config = config(), prompt = "initial continuation", operationId = "next",
            initialUserMessageId = "user-next-supplement-7", initialSupplementIndex = 7,
            runController = controller, provider = provider, toolExecutor = { error("no tool needed") },
        )
        assertEquals("user-next-supplement-8", result.transcript.first().messageId)
    }

    @Test
    fun characterReplacesIdentityButKeepsAgentToolsAndRealMemoryReadOnly() {
        val context = roleplay()
        val provider = provider { request ->
            val text = request.messages.toString()
            assertTrue(text.contains("Lin Zhou"))
            assertTrue(text.contains("tool contract"))
            assertFalse(text.contains("you are Atom."))
            assertFalse(text.contains("PROVIDER_IDENTITY"))
            assertFalse(text.contains("real-world tool operations are still performed by Atom"))
            assertTrue(text.contains("keep the character's identity and tone even when performing real-world tasks"))
            val names = (0 until request.tools.length()).map {
                request.tools.getJSONObject(it).getJSONObject("function").getString("name")
            }
            assertTrue("observe_screen" in names)
            assertTrue("memory_get" in names)
            assertFalse("memory_write" in names)
            assertTrue(CharacterMemoryTools.WRITE in names)
            reply("Let's go.")
        }
        val response = AgentModelClient.complete(
            config = config(), prompt = "travel together", roleplayContext = context,
            memoryContext = AgentMemoryContextBuilder.empty(null),
            additionalTools = JSONArray().also(CharacterMemoryTools::appendSchemas),
            provider = provider, toolExecutor = { error("no tool should run this time") },
            sessionId = "conversation", operationId = "run-one",
        )
        assertEquals("assistant-run-one-1", response.transcript.single().messageId)
    }

    @Test
    fun rewriteProducesOnlyTextWithoutReplacingMainContextOrExecutingTools() {
        var snapshots = 0
        val provider = provider { request ->
            assertEquals(ProviderRequestPurpose.REPLY_REWRITE, request.purpose)
            assertEquals(0, request.effectiveTools.length())
            assertFalse(request.effectiveConfig.hostedWebSearchEnabled)
            assertEquals("", request.effectiveConfig.extraBodyJson)
            assertTrue(request.messages.toString().contains("the original actual result"))
            reply("the rewritten result")
        }
        val result = AgentModelClient.complete(
            config = config().copy(hostedWebSearchEnabled = true, extraBodyJson = "{\"tools\":[{}]}"),
            prompt = "the original actual result", roleplayContext = roleplay(), rewriteReply = true,
            provider = provider, toolExecutor = { error("rewriting must not execute any tool") },
            onContextSnapshot = { snapshots++ },
        )
        assertEquals("the rewritten result", result.content)
        assertNull(result.contextSnapshot)
        assertEquals(0, snapshots)
    }

    @Test
    fun unexpectedToolCallDuringRewriteFailsBeforeExecutionOrTranscriptCommit() {
        var executions = 0
        var transcriptCommits = 0
        val provider = provider {
            ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                .put("finish_reason", "tool_calls")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", "danger")
                    .put("type", "function").put("function", JSONObject().put("name", "get_current_context")
                        .put("arguments", "{}")))))
        }
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(
                config = config(), prompt = "rephrase", rewriteReply = true,
                provider = provider,
                toolExecutor = { executions++; AgentModelClient.ToolResult("{}") },
                onTranscript = { transcriptCommits++ },
            )
        }
        assertEquals("REPLY_REWRITE_TOOL_CALL", (failure.cause as AgentModelFailure).code)
        assertEquals(0, executions)
        assertEquals(0, transcriptCommits)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun worldbookUsesCurrentDialogueAndDepthNeverSplitsToolExchange() {
        val context = RoleplayRunContext(
            "fixture", CharacterCardCodec.decodeJson("""{
                "name":"Lin Zhou","description":"traveler",
                "character_book":{"scan_depth":1,"entries":[
                    {"keys":["clock tower"],"content":"a messenger lives at the top of the tower","enabled":true}
                ]},"extensions":{"depth_prompt":{"prompt":"depth setting","depth":1,"role":"user"}}
            }"""), "user", "", contextWindow = 16_000,
        )
        val source = JSONArray().put(context.personaMessage())
            .put(AgentConversationCodec.userTextMessage("wait at the plaza"))
            .put(JSONObject().put("role", "assistant").put("content", "clock tower")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", "call"))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call").put("content", "clock tower"))
        val original = source.toString()
        val first = context.projectMessages(source)
        assertFalse(first.toString().contains("a messenger lives at the top of the tower"))
        assertEquals(original, source.toString())
        val toolCallIndex = (0 until first.length()).first { first.getJSONObject(it).has("tool_calls") }
        assertEquals("tool", first.getJSONObject(toolCallIndex + 1).getString("role"))
        source.put(AgentConversationCodec.userTextMessage("let's go to the clock tower"))
        assertTrue(context.projectMessages(source).toString().contains("a messenger lives at the top of the tower"))
    }

    @Test
    fun worldbookDoesNotScanImageUrlsOrStructuredContentFieldNames() {
        val context = RoleplayRunContext("fixture", CharacterCardCodec.decodeJson("""{
            "name":"Lin Zhou","character_book":{"entries":[
                {"keys":["image_url"],"content":"must not be triggered by structural fields"},
                {"keys":["tower.png"],"content":"must not be triggered by image links"},
                {"keys":["clock tower"],"content":"the setting triggered by body text"}
            ]}
        }"""), "user", "", contextWindow = 128_000)
        val input = JSONArray().put(context.personaMessage()).put(JSONObject().put("role", "user").put("content",
            JSONArray().put(JSONObject().put("type", "text").put("text", "head to the clock tower"))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "https://example.com/tower.png")))))
        val persona = context.projectMessages(input).getJSONObject(0).getString("content")
        assertTrue(persona.contains("the setting triggered by body text"))
        assertFalse(persona.contains("must not be"))
    }

    @Test
    fun worldbookBudgetUsesConfiguredWindowWithoutEstimatingDialogue() {
        val lore = "world knowledge".repeat(1_000)
        val card = CharacterCardCodec.decodeJson(JSONObject().put("name", "Lin Zhou")
            .put("character_book", JSONObject().put("entries", JSONArray().put(JSONObject()
                .put("constant", true).put("content", lore)))).toString())
        val context = RoleplayRunContext("fixture", card, "user", "", contextWindow = 64_000)
        val source = JSONArray().put(context.personaMessage()).put(AgentConversationCodec.userTextMessage("depart"))
        assertTrue(context.projectMessages(source).toString().contains(lore))
        source.put(JSONObject().put("role", "assistant").put("content", "long history".repeat(30_000)))
        assertTrue(context.projectMessages(source).toString().contains(lore))
        assertFalse(context.copy(contextWindow = 8_000).projectMessages(source).toString().contains(lore))
    }

    @Test
    fun providerProjectionKeepsDialogueDepthAndSystemTextWithoutInternalIdentifiers() {
        listOf("user", "assistant", "system").forEach { depthRole ->
            val card = CharacterCardCodec.decodeJson("""{
                "name":"Lin Zhou","extensions":{"depth_prompt":{"prompt":"depth setting","depth":1,"role":"$depthRole"}}
            }""")
            val context = RoleplayRunContext("fixture", card, "user", "", contextWindow = 128_000)
            val source = JSONArray().put(context.personaMessage())
                .put(AgentConversationCodec.userTextMessage("first sentence").put("_atom_message_id", "first-id"))
                .put(JSONObject().put("role", "assistant").put("content", "second sentence"))
            val projected = context.projectMessages(source)
            val chat = OpenAiRequestMessages.forChatCompletions(projected)
            val responses = ResponsesRequestBuilder.build(config(), projected, JSONArray())
            assertTrue(chat.toString().contains("depth setting"))
            assertTrue(responses.toString().contains("depth setting"))
            assertFalse(chat.toString().contains("_atom_message_id"))
            assertFalse(responses.toString().contains("_atom_message_id"))
            if (depthRole != "system") {
                val chatTexts = (0 until chat.length()).map { chat.getJSONObject(it).optString("content") }
                assertTrue(chatTexts.indexOf("first sentence") < chatTexts.indexOf("depth setting"))
                assertTrue(chatTexts.indexOf("depth setting") < chatTexts.indexOf("second sentence"))
                val input = responses.getJSONArray("input").toString()
                assertTrue(input.indexOf("first sentence") < input.indexOf("depth setting"))
                assertTrue(input.indexOf("depth setting") < input.indexOf("second sentence"))
            }
        }
    }

    @Test
    fun roleplaySummarySeparatesStoryFromRealExecution() {
        val provider = provider { request ->
            assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
            assertTrue(request.messages.toString().contains("fictional plot and real device operations are recorded separately"))
            reply("Plot: the two meet at the foot of the tower. Reality: no device operations were performed yet.")
        }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed rule"))
        repeat(8) { index ->
            messages.put(AgentConversationCodec.userTextMessage("act $index".repeat(300)))
            messages.put(JSONObject().put("role", "assistant").put("content", "story content".repeat(300)))
        }
        val result = AgentContextCompactor(config(), provider, AgentRunController(), roleplay = true)
            .compact(messages, 1, emptySet())
        assertTrue(result.toString().contains("Plot: the two meet at the foot of the tower"))
    }

    private fun roleplay() = RoleplayRunContext(
        "fixture", CharacterCardCodec.create("Lin Zhou").withEdits(description = "a traveler"), "companion", "",
        contextWindow = 128_000,
    )

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture",
        contextWindow = 128_000,
        systemPrompt = "PROVIDER_IDENTITY", browserTools = false,
    )

    private fun reply(text: String) = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", "stop"))

    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = OpenAiChatCompletionsProvider.capabilities
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
            block(request)
    }
}
