package io.github.namanwtf.atom.agent.model

import io.github.namanwtf.atom.agent.runtime.AgentEvent
import io.github.namanwtf.atom.agent.runtime.AgentRunController

/** Retries wrap only the model request; no history is committed and no local tools are executed until a complete response returns. */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var hostedToolStarted = false
            var callbackFailed = false
            try {
                val response = provider.complete(request, controller) { event ->
                    if (event is ProviderEvent.HostedToolStarted) hostedToolStarted = true
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Exception) {
                        callbackFailed = true
                        throw failure
                    }
                }
                return Result(round, response)
            } catch (failure: Exception) {
                controller.throwIfCancelled()
                if (callbackFailed || Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                if (hostedToolStarted) throw AgentModelFailure(
                    classified.code, false, classified.message.orEmpty(), classified,
                )
                if (!classified.retryable) throw classified
                if (retries == MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} was retried $MAX_RETRIES times and still has not recovered; previously completed tool results were kept.",
                        classified,
                    )
                }
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), classified.code))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // The display keeps failed attempts, while the model context and final thinking summary accept only successful attempts.
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L
    }
}
