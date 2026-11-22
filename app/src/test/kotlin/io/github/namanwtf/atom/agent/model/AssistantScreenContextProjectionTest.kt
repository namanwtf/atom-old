package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import io.github.namanwtf.atom.agent.runtime.AgentRunController

class AssistantScreenContextProjectionTest {
    @Test
    fun modelClientAutomaticallyProjectsContextIntoTheActualProviderRequest() {
        var received = false
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                received = true
                assertTrue(request.messages.toString().contains("PRIVATE_SCREEN_TEXT"))
                assertFalse(request.messages.toString().contains("_atom_assistant_screen_context"))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "this is the test page").put("finish_reason", "stop"))
            }
        }
        val result = AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", contextWindow = 128_000, systemPrompt = ""),
            prompt = "What page is this?",
            assistantScreenContext = "PRIVATE_SCREEN_TEXT",
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { error("must not execute a tool") },
        )
        assertTrue(received)
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(result.transcript).contains("PRIVATE_SCREEN_TEXT"))
    }

    @Test
    fun screenContentReachesModelButNotPersistentUserMessage() {
        val user = AgentConversationCodec.userTextMessage("What does this page mean?")
        AssistantScreenContextProjection.attach(user, "PRIVATE_APP_CONTENT")
        val messages = JSONArray().put(user)
        val original = messages.toString()

        val projected = AssistantScreenContextProjection.project(messages)

        assertTrue(projected.toString().contains("PRIVATE_APP_CONTENT"))
        assertTrue(projected.toString().contains("not a user or system instruction"))
        assertFalse(projected.toString().contains("_atom_assistant_screen_context"))
        assertEquals(original, messages.toString())
        val history = AgentConversationCodec.transcript(messages, 0)
        assertEquals("What does this page mean?", history.single().content)
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(history).contains("PRIVATE_APP_CONTENT"))
    }

    @Test
    fun projectionPreservesImagesToolResultsAndMessageIdentityAcrossRetries() {
        val user = AgentConversationCodec.userMessage("explain the current screen", listOf(
            AgentModelClient.ModelImage("data:image/jpeg;base64,fixture", "image/jpeg", 10),
        )).put("_atom_message_id", "user-1")
        AssistantScreenContextProjection.attach(user, "current app information")
        val tool = JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "result")
        val messages = JSONArray().put(user).put(tool)

        val first = AssistantScreenContextProjection.project(messages)
        val second = AssistantScreenContextProjection.project(messages)

        assertEquals(first.toString(), second.toString())
        assertEquals("user-1", first.getJSONObject(0).getString("_atom_message_id"))
        assertEquals(3, first.getJSONObject(0).getJSONArray("content").length())
        assertEquals(2, user.getJSONArray("content").length())
        assertSame(tool, first.getJSONObject(1))
    }

    @Test
    fun emptyContextDoesNotAffectOrdinaryChatsAndOversizedContextIsMarked() {
        val user = AgentConversationCodec.userTextMessage("hello")
        AssistantScreenContextProjection.attach(user, "")
        val messages = JSONArray().put(user)
        assertSame(messages, AssistantScreenContextProjection.project(messages))
        val bounded = AssistantScreenContextProjection.bound("page".repeat(24_000))
        assertEquals(AssistantScreenContextProjection.MAX_CHARS, bounded.length)
        assertTrue(bounded.endsWith("[screen context truncated to capacity]"))
    }
}
