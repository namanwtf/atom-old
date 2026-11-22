package io.github.namanwtf.atom.agent.runtime

import android.app.Application
import io.github.namanwtf.atom.agent.model.AgentModelClient
import io.github.namanwtf.atom.data.db.AtomDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRuntimePreparationFailureTest {
    @Test
    fun failureWithoutCheckpointIsPersistedBeforeTerminalDelivery() {
        val context = RuntimeEnvironment.getApplication()
        AtomDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val delivered = CountDownLatch(1)
            var storedBeforeDelivery = false
            val request = AgentRuntimeWire.RunRequest(
                runId = "preparation-failed",
                prompt = "test request",
                config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
                images = emptyList(),
                handoff = AgentRuntimeWire.EntryHandoff("preparation-failed", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"),
            )
            val run = RuntimeRunRegistry.Run(request, AgentRuntimeSession(request.runId, resultSink = {
                storedBeforeDelivery = AgentRuntimeResultStore.list(context).any { it.result.runId == request.runId }
                delivered.countDown()
            }))
            AgentRuntimeService::class.java.getDeclaredMethod("failBeforeExecution", RuntimeRunRegistry.Run::class.java, String::class.java).apply {
                isAccessible = true
            }.invoke(serviceController.get(), run, "cannot prepare the request")
            assertTrue(delivered.await(10, TimeUnit.SECONDS))
            assertTrue(storedBeforeDelivery)
            assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
            assertEquals("cannot prepare the request", AgentRuntimeResultStore.list(context).single().result.error)
            AgentRuntimeResultStore.remove(context, request.runId)
            assertTrue(AgentRuntimeResultStore.list(context).isEmpty())
        } finally {
            serviceController.destroy()
            AtomDatabase.closeForTests()
        }
    }
}
