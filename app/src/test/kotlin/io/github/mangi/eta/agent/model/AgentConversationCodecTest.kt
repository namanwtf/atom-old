package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentLegacyConversationProjection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationCodecTest {
    @Test
    fun toolRoundTripPreservesReasoningContentForCompatibleProviders() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("reasoning_content", "analyze the tool arguments first")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "device_info")
                                .put("arguments", "{}")
                        )
                )
            )

        val durable = AgentConversationCodec.durableMessage(assistant)
        val replayed = AgentConversationCodec.toJsonObject(durable)

        assertEquals("analyze the tool arguments first", replayed.getString("reasoning_content"))
        assertEquals("call-1", replayed.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test
    fun durableImageObservationNeverPersistsBase64Payload() {
        val message = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userMessage(
                text = "screen observation",
                images = listOf(
                    AgentModelClient.ModelImage(
                        reference = "data:image/png;base64,${"A".repeat(20_000)}",
                        mimeType = "image/png",
                        bytes = 15_000,
                    )
                ),
            )
        )

        assertFalse(message.contentJson.contains("base64"))
        assertTrue(message.contentJson.contains("not persisted to session"))
    }

    @Test
    fun ipcTranscriptHasHardBudgetAndNeverStartsWithOrphanToolResult() {
        val messages = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "answer-$index-${"x".repeat(20_000)}",
                    )
                )
                add(
                    AgentModelClient.ConversationMessage(
                        role = "tool",
                        toolCallId = "call-$index",
                        content = "result-${"y".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "assistant", content = "final answer"))
        }

        val encoded = AgentLegacyConversationProjection.encode(messages, AgentLegacyConversationProjection.DIRECT_CHARS)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length <= AgentLegacyConversationProjection.DIRECT_CHARS)
        assertTrue(decoded.isNotEmpty())
        assertFalse(decoded.first().role == "tool")
        assertTrue(decoded.first().content.contains("compressed at capacity limit"))
        assertTrue(decoded.last().content.contains("final answer"))
    }

    @Test
    fun conversationCheckpointPreservesEveryMessageBeyondLegacyBudget() {
        val messages = buildList {
            repeat(20) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "answer-$index-${"x".repeat(20_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "continue with the latest task"))
        }

        val encoded = AgentConversationCodec.encodeConversationCheckpoint(messages)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length > 96_000)
        assertEquals(messages, decoded)
        assertEquals("continue with the latest task", decoded.last().content)
    }

    @Test
    fun responsesOutputItemsStayInMemoryAndNeverEnterStableTranscript() {
        val source = JSONObject().put("role", "assistant").put("content", "done")
        ResponsesEphemeralState.attachOutputItems(
            source,
            JSONArray().put(
                JSONObject()
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque-secret"),
            ),
        )
        val history = AgentConversationCodec.assistantHistoryMessage(source, emptyList())
        assertTrue(ResponsesEphemeralState.outputItems(history) != null)

        val stable = AgentConversationCodec.durableMessage(history)
        val encoded = AgentConversationCodec.encodeTranscriptForStorage(listOf(stable))
        assertFalse(encoded.contains("opaque-secret"))
        assertFalse(encoded.contains("_eta_responses_output_items"))
    }
}
