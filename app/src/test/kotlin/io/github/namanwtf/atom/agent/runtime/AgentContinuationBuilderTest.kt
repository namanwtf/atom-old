package io.github.namanwtf.atom.agent.runtime

import io.github.namanwtf.atom.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContinuationBuilderTest {
    @Test
    fun rewriteAndCompactionCannotBecomeAnOrdinaryToolEnabledContinuation() {
        listOf(AgentRuntimeWire.OP_REWRITE_REPLY, AgentRuntimeWire.OP_COMPACT).forEach { operation ->
            val request = AgentRuntimeWire.RunRequest(
                runId = "run-old", prompt = "original character reply", config = modelConfig(), images = emptyList(),
                operation = operation, rewriteTargetMessageId = "assistant-old",
                handoff = AgentRuntimeWire.EntryHandoff("run-old", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"),
            )
            assertThrows(IllegalArgumentException::class.java) {
                AgentContinuationBuilder.build(request, AgentModelClient.ModelResponse.Text("rewrite the body"), "continue")
            }
        }
    }

    @Test
    fun continuationPreservesPromptImagesAndFullTranscript() {
        val image = AgentModelClient.ModelImage(
            reference = "data:image/png;base64,AA==",
            mimeType = "image/png",
            bytes = 1,
        )
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-old",
            prompt = "observe the screen",
            config = modelConfig(),
            images = listOf(image),
            history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "an earlier question")),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "run-old",
                source = "agent_ui",
                payload = AgentUiHandoffPayload(conversationId = "conversation-1").toJson(),
            ),
        )
        val response = AgentModelClient.ModelResponse.Text(
            content = "done",
            transcript = listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    toolCallsJson = "[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"observe_screen\",\"arguments\":\"{}\"}}]",
                ),
                AgentModelClient.ConversationMessage(
                    role = "tool",
                    toolCallId = "call-1",
                    content = "{\"ok\":true}",
                ),
                AgentModelClient.ConversationMessage(role = "assistant", content = "done"),
            ),
        )

        val continuation = AgentContinuationBuilder.build(
            request = request,
            response = response,
            supplement = "continue checking",
            newRunId = "run-next",
            createdAt = 123L,
        )

        assertEquals("conversation-1", continuation.effectiveModelSessionId)
        assertEquals("run-next", continuation.runId)
        assertEquals("continue checking", continuation.prompt)
        assertTrue(continuation.images.isEmpty())
        assertEquals(
            listOf("user", "user", "assistant", "tool", "assistant"),
            continuation.history.map { it.role },
        )
        assertTrue(continuation.history[1].contentJson.contains("not persisted to session"))
        assertEquals("user-run-old", continuation.history[1].messageId)
        assertTrue(!continuation.history[1].contentJson.contains("base64"))
        assertEquals("call-1", continuation.history[3].toolCallId)
        assertEquals("run-next", continuation.handoff?.id)
        val payload = AgentUiHandoffPayload.from(continuation.handoff?.payload.orEmpty())
        assertEquals("conversation-1", payload.conversationId)
        assertEquals(
            AgentUiHandoffPayload.Supplement(
                index = 1,
                text = "continue checking",
                createdAt = 123L,
            ),
            payload.promptSupplement,
        )
        assertTrue(payload.supplements.isEmpty())
    }

    @Test
    fun entryContinuationKeepsInitialSessionAcrossRuns() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "entry-run", prompt = "start", config = modelConfig(), images = emptyList(),
        )
        val continuation = AgentContinuationBuilder.build(
            request, AgentModelClient.ModelResponse.Text("done"), "continue", newRunId = "next-run",
        )
        assertEquals("entry-run", continuation.effectiveModelSessionId)
    }

    @Test
    fun continuationHistoryPreservesTheInitialSupplementMessageIdentifier() {
        val payload = AgentUiHandoffPayload("conversation", promptSupplement = AgentUiHandoffPayload.Supplement(7, "continuation body", 1L))
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-old", prompt = "continuation body", config = modelConfig(), images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff("run-old", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, payload.toJson()),
        )
        val continuation = AgentContinuationBuilder.build(request, AgentModelClient.ModelResponse.Text("done"), "continue")
        assertEquals("user-run-old-supplement-7", continuation.history.single().messageId)
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
        )
}
