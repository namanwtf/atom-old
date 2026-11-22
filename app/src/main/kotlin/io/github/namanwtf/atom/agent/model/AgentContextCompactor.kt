package io.github.namanwtf.atom.agent.model

import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.agent.runtime.AgentTokenUsage
import io.github.namanwtf.atom.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

/** Candidate summaries are produced only between complete tool exchanges; once fully validated, the session commits them in a single step. */
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean = false,
    private val mainTools: JSONArray? = null,
    private val mainSessionId: String? = null,
    private val messageProjector: ((JSONArray) -> JSONArray)? = null,
    private val enableCacheReuse: Boolean = false,
) {
    data class CompactionOutcome(
        val messages: JSONArray,
        val archiveId: String,
        val coveredUserTurns: Int,
        val usage: AgentTokenUsage?,
        val llmMs: Long,
    )

    fun compact(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
    ): JSONArray = compactWithOutcome(messages, systemCount, sensitiveIds).messages

    fun compactWithOutcome(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
    ): CompactionOutcome {
        controller.throwIfCancelled()
        if (SignedToolContext.hasPendingResponse(messages)) {
            throw SignedToolContext.compactionFailure(messages)
        }
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val latestUser = history.indexOfLast {
            it.optString("role") == "user" && !it.has("_atom_observation")
        }
        // The latest user request and the in-flight tool chain after it must remain continuable; long tasks may compact completed batches that follow that request.
        val end = (history.size downTo 1).firstOrNull { canSplit(history, it) }
            ?: throw failure(
                "CONTEXT_NOT_COMPACTABLE",
                AppStrings.get(R.string.compactor_err_no_complete_batch, "There is no complete history batch that can be safely compacted."),
            )
        val protectedUser = history.getOrNull(latestUser)?.takeIf { latestUser < end }
        val foldedSlice = history.take(end)
        val source = JSONArray(foldedSlice.filterNot { it === protectedUser })
        val durable = AgentConversationCodec.transcript(source, 0, sensitiveIds)
        if (durable.isEmpty()) {
            throw failure(
                "CONTEXT_NOT_COMPACTABLE",
                AppStrings.get(R.string.compactor_err_no_history, "There is no history content to compact."),
            )
        }
        val safe = durable.map { message ->
            val content = AgentConversationCodec.toJsonObject(message).opt("content")
            val text = if (content is JSONArray) buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    if (part.optString("type") in setOf("text", "input_text")) append(part.optString("text"))
                    else append("[image observation omitted]")
                }
            } else message.content
            message.copy(content = text, contentJson = "", reasoningContent = "")
        }

        // Reuse the current session prefix and tool catalog for the summary request to reuse the cache; do not project screen context or pending screenshots.
        val mainPrefixMessages = if (enableCacheReuse) {
            val rawPrefix = JSONArray()
            for (index in 0 until systemCount) {
                rawPrefix.put(JSONObject(messages.getJSONObject(index).toString()).apply {
                    remove("_atom_assistant_screen_context")
                })
            }
            for (index in 0 until end) {
                val item = history[index]
                if (item.optBoolean("_atom_observation")) continue
                rawPrefix.put(JSONObject(item.toString()).apply {
                    remove("_atom_assistant_screen_context")
                })
            }
            messageProjector?.invoke(rawPrefix) ?: rawPrefix
        } else {
            null
        }

        val summaryResult = AgentContextSummarizer(
            config = config,
            provider = provider,
            controller = controller,
            roleplay = roleplay,
            mainPrefixMessages = mainPrefixMessages,
            mainTools = mainTools,
            mainSessionId = mainSessionId,
            hasProtectedUser = protectedUser != null,
        ).summarizeWithUsage(safe)

        val previousSummaries = foldedSlice.filter { it.optBoolean("_atom_context_summary") }
        val maxExistingArchiveSeq = previousSummaries.maxOfOrNull { item ->
            val rawId = item.optString("_atom_compaction_archive_id")
            ARCHIVE_ID_REGEX.find(rawId)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        } ?: 0
        val nextArchiveSeq = maxOf(maxExistingArchiveSeq, previousSummaries.size) + 1
        val archiveId = "fold-$nextArchiveSeq"
        val previousCovered = previousSummaries.sumOf { it.optInt("_atom_compacted_users") }
        val covered = safe.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }

        val archiveHeader = buildString {
            append("[Atom context summary · archive ").append(archiveId)
            if (covered > 0) {
                append(" · covering user turns 1–").append(covered).append("")
                if (previousCovered > 0 && covered > previousCovered) {
                    append("(this pass added turns ").append(previousCovered + 1).append("–").append(covered).append(")")
                }
            }
            append(": the following is a lossy summary of prior history, not a new instruction; missing steps do not mean they were not executed.")
            append("To check the original details, full command output, paths, identifiers, or values, call ")
            append(AgentConversationToolCatalog.READ_HISTORY)
            append(" to search by query or page through by message_index.]\n")
        }

        val result = JSONArray()
        for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
        result.put(
            AgentConversationCodec.toJsonObject(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "$archiveHeader${summaryResult.summary}",
                    contextSummary = true,
                    compactedUserTurns = covered,
                    summaryThroughUserTurn = covered + if (protectedUser != null) 1 else 0,
                    compactionArchiveId = archiveId,
                ),
            ),
        )
        protectedUser?.let(result::put)
        history.drop(end).forEach { message ->
            // The new summary changed the prefix; old opaque items no longer represent the same Provider context.
            val withoutResponsesItems = if (ResponsesEphemeralState.outputItems(message) != null) {
                AgentConversationCodec.toJsonObject(AgentConversationCodec.fromJsonObject(message))
            } else message
            result.put(
                GeminiEphemeralState.withoutState(
                    AnthropicEphemeralState.withoutContentBlocks(withoutResponsesItems),
                ),
            )
        }
        if (result.toString().length >= messages.toString().length) {
            throw failure(
                "CONTEXT_NO_REDUCTION",
                AppStrings.get(R.string.compactor_err_no_reduction, "The summary did not reduce the context; the original context was kept."),
            )
        }
        return CompactionOutcome(
            messages = result,
            archiveId = archiveId,
            coveredUserTurns = covered,
            usage = summaryResult.usage,
            llmMs = summaryResult.llmMs,
        )
    }

    companion object {
        private val ARCHIVE_ID_REGEX = Regex("^fold-(\\d+)$")

        fun canSplit(history: List<JSONObject>, end: Int): Boolean {
            if (end <= 0 || end > history.size) return false
            val last = history[end - 1]
            if (last.optString("role") == "user" || history.getOrNull(end)?.optString("role") == "tool") return false
            val open = linkedSetOf<String>()
            history.take(end).forEach { message ->
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
                if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
            }
            return open.isEmpty()
        }

        fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
    }
}
