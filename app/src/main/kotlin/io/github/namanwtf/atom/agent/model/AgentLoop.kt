package io.github.namanwtf.atom.agent.model

import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.runtime.AgentEvent
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.agent.runtime.AgentTokenUsage
import io.github.namanwtf.atom.agent.roleplay.RoleplayRunContext
import io.github.namanwtf.atom.agent.subagent.AgentSubagentToolCatalog
import io.github.namanwtf.atom.core.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure orchestration loop for a single Agent run.
 *
 * One assistant response together with its complete tool batch forms a turn;
 * steering is injected only after a turn ends and cannot be simulated by cancelling the network or closing tool resources. The loop sets no local turn limit
 * and is terminated by the model finishing naturally, cancellation, or an error.
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val transcript: JSONArray = JSONArray(),
    private val systemCount: Int = 0,
    private val operationId: String = sessionId,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit = {},
    private val onTranscript: (List<AgentModelClient.ConversationMessage>) -> Unit = {},
    private val purpose: ProviderRequestPurpose = ProviderRequestPurpose.CHAT,
    private val roleplayContext: RoleplayRunContext? = null,
    initialSupplementIndex: Int = 0,
) {
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var availableToolNames = emptySet<String>()
    private val accumulatedReasoning = StringBuilder()
    private val thinkingBlockStartMs = mutableMapOf<Pair<Int, Int>, Long>()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private val toolBatchLock = Any()
    private var pendingToolImageMessage: JSONObject? = null
    private var pendingToolResultImages = emptyMap<String, List<AgentModelClient.ModelImage>>()
    private val turnMetrics = TurnMetricsAccumulator()
    private var precedingTools: JSONArray? = null
    private val context = AgentContextSession(
        config, messages, systemCount, operationId, provider, runController,
        { sensitiveToolCallIds }, onEvent, onContextSnapshot, { transcript.length() },
        roleplay = roleplayContext != null,
        mainToolsProvider = { precedingTools ?: tools },
        mainSessionId = sessionId,
        messageProjector = { source -> roleplayContext?.projectMessages(source) ?: source },
        enableCacheReuse = true,
    )
    private var supplementIndex = initialSupplementIndex

    fun contextSnapshot(): AgentContextSnapshot? = context.snapshot()

    private fun appendMessage(message: JSONObject) {
        messages.put(message)
        transcript.put(message)
    }

    private var publishedTranscriptSize = 0

    private fun publishTranscript() {
        if (publishedTranscriptSize == transcript.length()) return
        onTranscript(AgentConversationCodec.transcript(transcript, 0, sensitiveToolCallIds))
        publishedTranscriptSize = transcript.length()
    }

    fun compactOnly(): Result {
        context.compact(force = true)
        return Result("", "", emptySet())
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = 1

        while (true) {
            runController.throwIfCancelled()
            if (purpose.allowsTools) appendPendingSteeringMessage()

            // A signed tool chain keeps the request prefix it had when the call was issued; refresh the catalog only after the tool chain completes.
            val roundTools = if (SignedToolContext.hasPendingResponse(messages)) {
                precedingTools ?: tools
            } else if (purpose.allowsTools) {
                toolsForRound?.invoke() ?: tools
            } else {
                JSONArray()
            }
            precedingTools = roundTools
            availableToolNames = (0 until roundTools.length()).mapNotNullTo(mutableSetOf()) { index ->
                roundTools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
            }
            publishTranscript()
            if (purpose.allowsTools) context.compact()
            val requestMessages = AssistantScreenContextProjection.project(
                roleplayContext?.projectMessages(messages) ?: messages,
            )
            var roundInputTokens: Int? = null
            var roundUsage: AgentTokenUsage? = null
            var stepStartedAtMs = monotonicNowMs()
            var firstTokenAtMs: Long? = null
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(config, requestMessages, roundTools, sessionId, purpose, pendingToolResultImages),
                    provider = provider,
                    controller = runController,
                    onEvent = { event ->
                        if (event is AgentEvent.RoundStarted) {
                            roundInputTokens = null
                            roundUsage = null
                            stepStartedAtMs = monotonicNowMs()
                            firstTokenAtMs = null
                        }
                        onEvent(event)
                    },
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (!purpose.allowsTools && (providerEvent is ProviderEvent.HostedToolStarted ||
                                providerEvent is ProviderEvent.SearchSuggestions ||
                                providerEvent is ProviderEvent.BlockStart && providerEvent.kind == AssistantBlockKind.TOOL_CALL)) {
                            throw AgentModelFailure(
                                "REPLY_REWRITE_TOOL_CALL",
                                false,
                                AppStrings.get(
                                    R.string.loop_err_reply_rewrite_tool_call,
                                    "While rewriting the reply the model requested a tool, so it was stopped; the original reply is unchanged.",
                                ),
                            )
                        }
                        if (providerEvent is ProviderEvent.RequestStarted) {
                            stepStartedAtMs = monotonicNowMs()
                            firstTokenAtMs = null
                        }
                        if (firstTokenAtMs == null &&
                            providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.delta.isNotEmpty()
                        ) {
                            firstTokenAtMs = monotonicNowMs()
                        }
                        if (providerEvent is ProviderEvent.Usage) {
                            roundUsage = providerEvent.usage
                            roundInputTokens = providerEvent.contextInputTokens ?: roundInputTokens
                        }
                        if (providerEvent is ProviderEvent.BlockStart &&
                            providerEvent.kind == AssistantBlockKind.THINKING
                        ) {
                            thinkingBlockStartMs[attemptRound to providerEvent.index] = monotonicNowMs()
                        }
                        if (providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.kind == AssistantBlockKind.THINKING
                        ) {
                            thinkingBlockStartMs.putIfAbsent(attemptRound to providerEvent.index, monotonicNowMs())
                            accumulatedReasoning.append(providerEvent.delta)
                        }
                        if (providerEvent !is ProviderEvent.Usage) {
                            providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                        }
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                ).also { response ->
                    rejectContentFilter(response.response)
                    if (purpose == ProviderRequestPurpose.CHAT) validateChatResponse(response.response)
                }
            } finally {
                // Retries in the same turn still need the original observation; the screenshot is removed only after the whole turn ends.
                clearPendingToolImages()
            }
            val completedAtMs = monotonicNowMs()
            val stepLlmMs = (completedAtMs - stepStartedAtMs).coerceAtLeast(0L)
            val stepTtftMs = firstTokenAtMs?.let { (it - stepStartedAtMs).coerceAtLeast(0L) }
            val stepDecodeMs = firstTokenAtMs?.let { (completedAtMs - it).coerceAtLeast(0L) }
            context.observeInputTokens(roundInputTokens)
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            if (!purpose.allowsTools && toolCalls.isNotEmpty()) {
                throw AgentModelFailure(
                    "REPLY_REWRITE_TOOL_CALL",
                    false,
                    AppStrings.get(
                        R.string.loop_err_reply_rewrite_tool_call,
                        "While rewriting the reply the model requested a tool, so it was stopped; the original reply is unchanged.",
                    ),
                )
            }
            if (purpose == ProviderRequestPurpose.REPLY_REWRITE && providerResponse.stopReason != AssistantStopReason.END_TURN) {
                throw AgentModelFailure(
                    "REPLY_REWRITE_INCOMPLETE",
                    false,
                    AppStrings.get(
                        R.string.loop_err_reply_rewrite_incomplete,
                        "The model did not return a complete rewritten reply; the original reply is unchanged.",
                    ),
                )
            }
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            appendMessage(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put("_atom_message_id", "assistant-$operationId-$round")
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )
            val accumulatedUsage = turnMetrics.recordStep(
                stepLlmMs = stepLlmMs,
                stepTtftMs = stepTtftMs,
                stepDecodeMs = stepDecodeMs,
                stepUsage = roundUsage,
                stepPromptTokens = roundInputTokens,
            )
            if (!accumulatedUsage.isEmpty) {
                onEvent(AgentEvent.UsageReceived(round = round, usage = accumulatedUsage))
            }

            if (toolCalls.isNotEmpty()) {
                val outcomes = executeToolBatch(round, toolCalls, providerResponse.stopReason)
                outcomes.forEach { outcome ->
                    appendMessage(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
                }
                publishTranscript()
                appendToolImages(round, outcomes)
                publishTranscript()
                round += 1
                continue
            }

            publishTranscript()

            // Check steering only after the assistant has finished naturally; this way the supplementary message does not drop the just-completed answer.
            if (purpose.allowsTools && appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                val message = if (finishReason.isNotBlank()) {
                    AppStrings.get(
                        R.string.loop_err_empty_response_reason,
                        "The model API returned empty on round %1\$d: %2\$s",
                        round,
                        finishReason,
                    )
                } else {
                    AppStrings.get(
                        R.string.loop_err_empty_response,
                        "The model API returned empty on round %1\$d",
                        round,
                    )
                }
                error(message)
            }

            publishTranscript()
            if (purpose.allowsTools) context.compact(final = true)
            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    /**
     * A refusal or filter truncates the reply mid-stream; the half text, thinking blocks, and tool calls must not enter the context:
     * continuing the request with a truncated signature block is judged by upstream as a modified thinking block, and every subsequent turn of the same session returns 400.
     * Therefore do not write to history and do not execute tools; end this run directly and let the user rewrite the request or switch models.
     */
    private fun rejectContentFilter(response: ProviderResponse) {
        if (response.stopReason != AssistantStopReason.CONTENT_FILTER) return
        val explanation = response.assistantMessage.optJSONObject("stop_details")
            ?.let { details -> details.optString("explanation").takeUnless { details.isNull("explanation") } }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        throw AgentModelFailure(
            "MODEL_CONTENT_FILTER",
            false,
            if (explanation != null) {
                AppStrings.get(
                    R.string.loop_err_content_filter_detail,
                    "The model provider blocked this reply and the incomplete content was discarded. Provider note: %1\$sRevise or remove the request that triggered the block and retry, or switch to another model.",
                    explanation,
                )
            } else {
                AppStrings.get(
                    R.string.loop_err_content_filter,
                    "The model provider blocked this reply and the incomplete content was discarded. Revise or remove the request that triggered the block and retry, or switch to another model.",
                )
            },
        )
    }

    private fun validateChatResponse(response: ProviderResponse) {
        val message = response.assistantMessage
        if (AgentConversationCodec.parseToolCalls(message).isNotEmpty()) return
        val content = message.optString("content").trim()
        if (content.isNotBlank() && content != "null") return
        throw when (response.stopReason) {
            AssistantStopReason.OUTPUT_LIMIT ->
                AgentModelFailure(
                    "MODEL_OUTPUT_LIMIT",
                    false,
                    AppStrings.get(
                        R.string.loop_err_output_limit,
                        "The model output quota was exhausted before any text was generated; check the output limit or lower the reasoning effort.",
                    ),
                )
            else -> AgentModelFailure(
                "MODEL_EMPTY_RESPONSE",
                false,
                AppStrings.get(
                    R.string.loop_err_empty_model_response,
                    "The model returned no text or tool call; check the provider status.",
                ),
            )
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        turnMetrics.reset()
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        appendMessage(steeringMessage(supplement))
        context.userAppended()
        turnMetrics.reset()
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "User supplemental instruction: $supplement\n\nContinue based on the current task context; do not redo from scratch operations that are already complete or verified."

    private fun steeringMessage(supplement: String): JSONObject =
        AgentConversationCodec.userTextMessage(steeringPrompt(supplement))
            .put("_atom_message_id", "user-$operationId-supplement-${++supplementIndex}")
            .put("_atom_steering", true)

    private fun executeToolBatch(
        round: Int,
        toolCalls: List<AgentModelClient.ToolCall>,
        stopReason: AssistantStopReason,
    ): List<ToolOutcome> {
        if (stopReason != AssistantStopReason.TOOL_USE) {
            return toolCalls.map { call ->
                when (stopReason) {
                    AssistantStopReason.OUTPUT_LIMIT -> rejectedToolOutcome(
                        round, call, "TRUNCATED_TOOL_CALL",
                        "The model output hit the length limit, so the tool arguments may be incomplete; this call was not executed. Please resubmit complete arguments.",
                    )
                    else -> rejectedToolOutcome(
                        round, call, "UNEXPECTED_TOOL_CALL",
                        "The model returned tool calls while in the ${stopReason.name} stop state; this batch was not executed. Please re-plan.",
                    )
                }
            }
        }

        val outcomes = mutableListOf<ToolOutcome>()
        var index = 0
        while (index < toolCalls.size) {
            val current = toolCalls[index]
            if (current.name == AgentSubagentToolCatalog.TOOL_NAME && current.name in availableToolNames) {
                val parallelBatch = mutableListOf<AgentModelClient.ToolCall>()
                while (
                    index < toolCalls.size &&
                    toolCalls[index].name == AgentSubagentToolCatalog.TOOL_NAME &&
                    toolCalls[index].name in availableToolNames
                ) {
                    parallelBatch += toolCalls[index].copy(round = round)
                    index += 1
                }
                if (parallelBatch.size == 1) {
                    outcomes += executeTool(round, parallelBatch.single())
                } else {
                    outcomes += executeParallelSubagents(round, parallelBatch)
                }
            } else {
                outcomes += executeTool(round, current.copy(round = round))
                index += 1
            }
        }
        return outcomes
    }

    private fun executeParallelSubagents(
        round: Int,
        calls: List<AgentModelClient.ToolCall>,
    ): List<ToolOutcome> {
        runController.throwIfCancelled()
        calls.forEach { toolCall ->
            synchronized(toolBatchLock) {
                onEvent(
                    AgentEvent.ToolStarted(
                        round = round,
                        toolCallId = toolCall.id,
                        name = toolCall.name,
                        argsPreview = traceFormatter.summarizeArguments(toolCall),
                        command = traceFormatter.displayCommand(toolCall),
                    ),
                )
            }
        }
        val outcomes = runBlocking(Dispatchers.IO) {
            supervisorScope {
                calls.map { toolCall ->
                    async {
                        val toolStartMs = monotonicNowMs()
                        val result = try {
                            toolExecutor.execute(toolCall)
                        } catch (throwable: Exception) {
                            if (runController.isCancelled) {
                                AgentModelClient.ToolResult(
                                    content = JSONObject()
                                        .put("ok", false)
                                        .put("code", "SUBAGENT_CANCELLED")
                                        .put("message", "Stopped")
                                        .toString(),
                                )
                            } else {
                                AgentModelClient.ToolResult(
                                    content = JSONObject()
                                        .put("ok", false)
                                        .put("code", "TOOL_ERROR")
                                        .put("message", throwable.message ?: throwable.javaClass.simpleName)
                                        .toString(),
                                )
                            }
                        }
                        val elapsedMs = (monotonicNowMs() - toolStartMs).coerceAtLeast(0L)
                        synchronized(toolBatchLock) {
                            if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                                sensitiveToolCallIds += toolCall.id
                            }
                            if (!runController.isCancelled) {
                                turnMetrics.recordToolMs(elapsedMs)
                                emitToolFinished(round, toolCall, result)
                            }
                        }
                        ToolOutcome(toolCall, result)
                    }
                }.awaitAll()
            }
        }
        runController.throwIfCancelled()
        return outcomes
    }

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        if (toolCall.name !in availableToolNames) {
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "TOOL_NOT_AVAILABLE",
                message = "This tool is not provided in this round's capability catalog; use a currently available tool",
            )
        }
        val roundCall = if (toolCall.round == round) toolCall else toolCall.copy(round = round)
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = roundCall.id,
                name = roundCall.name,
                argsPreview = traceFormatter.summarizeArguments(roundCall),
                command = traceFormatter.displayCommand(roundCall),
            )
        )

        val toolStartMs = monotonicNowMs()
        val result = try {
            toolExecutor.execute(roundCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        turnMetrics.recordToolMs((monotonicNowMs() - toolStartMs).coerceAtLeast(0L))
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(roundCall.name)) {
            sensitiveToolCallIds += roundCall.id
        }

        emitToolFinished(round, roundCall, result)
        return ToolOutcome(roundCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
                command = result.command,
            )
        )
    }

    private fun appendToolImages(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // Each completed result is persisted immediately; image observations are still placed uniformly after the complete tool batch.
        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) {
            return
        }

        // Tool screenshots are transient observations, not session assets. Delete them immediately after the next thinking consumes them.
        if (provider.capabilities.toolResultImages) {
            pendingToolResultImages = imageOutcomes.associate { it.call.id to it.result.images }
        } else {
            val toolNames = imageOutcomes.map { it.call.name }.distinct().joinToString(", ")
            pendingToolImageMessage = AgentConversationCodec.userMessage(
                text = "Latest observation image(s) returned by tool(s): $toolNames.",
                images = imageOutcomes.flatMap { it.result.images },
            ).put("_atom_observation", true).also(messages::put)
        }

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun clearPendingToolImages() {
        pendingToolResultImages = emptyMap()
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                startedAtEpochMs = if (kind == AssistantBlockKind.THINKING) System.currentTimeMillis() else null,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
                elapsedMs = if (kind == AssistantBlockKind.THINKING) {
                    thinkingBlockStartMs.remove(round to index)
                        ?.let { startedAt -> (monotonicNowMs() - startedAt).coerceAtLeast(0L) }
                } else {
                    null
                },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.SearchSuggestions -> AgentEvent.SearchSuggestions(round, index, html)
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

    private fun monotonicNowMs(): Long = System.nanoTime() / 1_000_000L

    private class TurnMetricsAccumulator {
        private var stepCount: Int = 0
        private var llmMs: Long = 0L
        private var toolMs: Long = 0L
        private var ttftMs: Long = 0L
        private var ttftSteps: Int = 0
        private var decodeMs: Long = 0L
        private var decodeTokens: Int = 0
        private var contextTokens: Int? = null
        private var inputTokens: Int? = null
        private var outputTokens: Int? = null
        private var reasoningTokens: Int? = null
        private var cachedTokens: Int? = null
        private var cacheWriteTokens: Int? = null

        fun reset() {
            stepCount = 0
            llmMs = 0L
            toolMs = 0L
            ttftMs = 0L
            ttftSteps = 0
            decodeMs = 0L
            decodeTokens = 0
            contextTokens = null
            inputTokens = null
            outputTokens = null
            reasoningTokens = null
            cachedTokens = null
            cacheWriteTokens = null
        }

        fun recordStep(
            stepLlmMs: Long,
            stepTtftMs: Long?,
            stepDecodeMs: Long?,
            stepUsage: AgentTokenUsage?,
            stepPromptTokens: Int? = null,
        ): AgentTokenUsage {
            stepCount += 1
            llmMs += stepLlmMs.coerceAtLeast(0L)
            if (stepTtftMs != null) {
                ttftMs += stepTtftMs.coerceAtLeast(0L)
                ttftSteps += 1
            }
            val stepOut = stepUsage?.outputTokens
            if (stepDecodeMs != null && stepOut != null && stepOut > 0) {
                decodeMs += stepDecodeMs.coerceAtLeast(0L)
                decodeTokens += stepOut
            }
            stepUsage?.contextTokens?.let { contextTokens = it }
            (stepPromptTokens ?: stepUsage?.inputTokens)?.let { inputTokens = (inputTokens ?: 0) + it }
            stepUsage?.outputTokens?.let { outputTokens = (outputTokens ?: 0) + it }
            stepUsage?.reasoningTokens?.let { reasoningTokens = (reasoningTokens ?: 0) + it }
            stepUsage?.cachedTokens?.let { cachedTokens = (cachedTokens ?: 0) + it }
            stepUsage?.cacheWriteTokens?.let { cacheWriteTokens = (cacheWriteTokens ?: 0) + it }
            return AgentTokenUsage(
                contextTokens = contextTokens,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                cachedTokens = cachedTokens,
                cacheWriteTokens = cacheWriteTokens,
                requestKind = AgentTokenUsage.REQUEST_KIND_CHAT,
                stepCount = stepCount.takeIf { it > 0 },
                llmMs = llmMs.takeIf { stepCount > 0 },
                toolMs = toolMs.takeIf { it > 0L },
                ttftMs = ttftMs.takeIf { ttftSteps > 0 },
                ttftSteps = ttftSteps.takeIf { it > 0 },
                decodeMs = decodeMs.takeIf { decodeTokens > 0 && it > 0L },
                decodeTokens = decodeTokens.takeIf { decodeMs > 0L && it > 0 },
            )
        }

        fun recordToolMs(elapsedMs: Long) {
            toolMs += elapsedMs.coerceAtLeast(0L)
        }
    }
}
