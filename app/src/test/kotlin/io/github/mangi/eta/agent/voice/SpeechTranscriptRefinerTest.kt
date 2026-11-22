package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.OpenAiChatCompletionsProvider
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderRequestPurpose
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeechTranscriptRefinerTest {
    private val requests = mutableListOf<ProviderRequest>()

    private fun config(capabilities: ModelReasoningCapabilities? = null) = AgentModelClient.ModelConfig(
        baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture",
        systemPrompt = "PROVIDER_IDENTITY", reasoningEffort = ReasoningEffort.HIGH,
        reasoningCapabilities = capabilities,
    )

    private fun refiner(
        config: AgentModelClient.ModelConfig? = config(),
        reply: (AgentRunController) -> ProviderResponse,
    ) = SpeechTranscriptRefiner(loadConfig = { config }, providerFor = {
        object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = OpenAiChatCompletionsProvider.capabilities
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
                reply(runController).also { requests += request }
        }
    })

    private fun reply(text: String, finish: String = "stop") = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", finish))

    @Test fun correctedTextIsReturnedFromToolFreeRequest() = runBlocking {
        val result = refiner { reply("parse JSON with Python") }.refine("parsse JSON with Pythan")
        assertEquals("parse JSON with Python", result)
        val request = requests.single()
        assertEquals(ProviderRequestPurpose.TRANSCRIPT_REFINE, request.purpose)
        assertEquals(0, request.effectiveTools.length())
        // Recognized text enters the request only as user data; the system prompt never concatenates user content.
        assertEquals(SpeechTranscriptRefiner.SYSTEM_PROMPT, request.messages.getJSONObject(0).getString("content"))
        assertTrue(request.messages.getJSONObject(1).toString().contains("parse JSON with Python"))
    }

    @Test fun suspiciousOrIncompleteOutputKeepsOriginal() = runBlocking {
        val original = "open settings for me"
        assertEquals(original, refiner { reply("Okay, I've already rephrased this sentence into a smoother expression for you: please open the system settings page for me") }.refine(original))
        assertEquals(original, refiner { reply("") }.refine(original))
        assertEquals(original, refiner { reply("open settings for me", finish = "length") }.runCatching { refine(original) }.getOrDefault(original))
    }

    @Test fun lengthLimitsSkipModelCall() = runBlocking {
        assertEquals("um", refiner { error("must not request the model") }.refine("um"))
        val long = "very long".repeat(1_001)
        assertEquals(long, refiner { error("must not request the model") }.refine(long))
    }

    @Test fun missingChatModelIsConfigurationFailure() = runBlocking {
        val failure = runCatching { refiner(config = null) { reply("x") }.refine("test text") }.exceptionOrNull()
        assertEquals(SpeechErrorCode.CONFIGURATION, (failure as SpeechFailure).code)
    }

    @Test fun reasoningUsesLowestSelectableEffort() = runBlocking {
        refiner(config(ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true,
        ))) { reply("test text") }.refine("test text")
        refiner(config(ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), mandatory = true,
        ))) { reply("test text") }.refine("test text")
        assertEquals(listOf(ReasoningEffort.OFF, ReasoningEffort.LOW), requests.map { it.config.reasoningEffort })
        // Models without declared reasoning capability keep the user config and don't send parameters they may not support.
        refiner { reply("test text") }.refine("test text")
        assertEquals(ReasoningEffort.HIGH, requests.last().config.reasoningEffort)
    }

    @Test fun timeoutCancelsBlockedProviderCall() {
        val cancelled = CountDownLatch(1)
        val failure = runCatching {
            runBlocking {
                refiner { controller ->
                    val released = CountDownLatch(1)
                    controller.register { cancelled.countDown(); released.countDown() }
                    released.await(30, TimeUnit.SECONDS)
                    reply("late")
                }.refine("test text")
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
    }
}
