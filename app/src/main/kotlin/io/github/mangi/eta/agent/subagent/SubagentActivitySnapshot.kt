package io.github.mangi.eta.agent.subagent

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

@Serializable
internal data class SubagentStepSnapshot(
    val id: String,
    val kind: String,
    val title: String = "",
    val detail: String = "",
    val status: String = STATUS_RUNNING,
    val toolName: String = "",
) {
    companion object {
        const val KIND_THINKING = "thinking"
        const val KIND_TOOL = "tool"

        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
    }
}

@Serializable
internal data class SubagentUsageSnapshot(
    val inputTokens: Int = 0,
    val cachedTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val llmMs: Long = 0L,
)

/**
 * Live and persisted structured activity snapshot of a Subagent.
 *
 * Stored in the `command` field of the parent `subagent` tool message, supporting both live inspection while running,
 * and clicking through to review the full internal process after archiving and restart.
 *
 * All cross-process or persisted transfers must go through [encodeForWire] / [boundedForWire], keeping the real UTF-16
 * length after serialization within the wire budget to avoid underlying truncation breaking the JSON structure.
 */
@Serializable
internal data class SubagentActivitySnapshot(
    val subagentId: String = "",
    val subagentType: String = SubagentProfile.DEFAULT_TYPE,
    val description: String = "",
    val prompt: String = "",
    val model: String = "",
    val resumed: Boolean = false,
    val status: String = STATUS_RUNNING,
    val currentAction: String = "",
    val steps: List<SubagentStepSnapshot> = emptyList(),
    val output: String = "",
    val error: String? = null,
    val usage: SubagentUsageSnapshot? = null,
    /** Cumulative tool-call count before early steps were dropped; defaults to 0 in old snapshots, falling back to the [steps] count on read. */
    val totalToolSteps: Int = 0,
    /** Number of early steps collapsed due to the step limit or wire budget. */
    val omittedSteps: Int = 0,
) {
    val toolStepCount: Int
        get() = maxOf(totalToolSteps, steps.count { it.kind == SubagentStepSnapshot.KIND_TOOL })

    fun encode(): String = json.encodeToString(this)

    fun encodeForWire(maxChars: Int = WIRE_BUDGET_CHARS): String =
        boundedForWire(maxChars).encode()

    /**
     * Shrinks the snapshot to within [maxChars] UTF-16 units based on its real serialized length while keeping the JSON valid.
     * Preferentially keeps status, usage, cumulative tool steps, and the latest steps, progressively compressing long reports, prompts, and early step details.
     */
    fun boundedForWire(maxChars: Int = WIRE_BUDGET_CHARS): SubagentActivitySnapshot {
        val budget = maxChars.coerceAtLeast(MIN_WIRE_BUDGET_CHARS)
        val preservedToolSteps = toolStepCount
        val normalizedBase = if (totalToolSteps != preservedToolSteps) {
            copy(totalToolSteps = preservedToolSteps)
        } else {
            this
        }
        if (normalizedBase.encode().length <= budget) return normalizedBase

        var candidate = normalizedBase.copy(
            subagentId = normalizedBase.subagentId.takeUtf16Safe(MAX_ID_CHARS),
            subagentType = normalizedBase.subagentType.takeUtf16Safe(MAX_TYPE_CHARS),
            description = normalizedBase.description.truncateWithEllipsis(MAX_DESCRIPTION_CHARS),
            prompt = normalizedBase.prompt.truncateWithEllipsis(MAX_PROMPT_CHARS),
            model = normalizedBase.model.takeUtf16Safe(MAX_MODEL_CHARS),
            currentAction = normalizedBase.currentAction.truncateWithEllipsis(MAX_ACTION_CHARS),
            output = normalizedBase.output.truncateWithEllipsis(MAX_OUTPUT_CHARS),
            error = normalizedBase.error?.truncateWithEllipsis(MAX_ERROR_CHARS),
            totalToolSteps = preservedToolSteps,
        )
        if (candidate.encode().length <= budget) return candidate

        // Level 1: first compress long reports and prompts, keeping the full step timeline.
        val textTiers = listOf(
            6_000 to 1_500,
            3_000 to 600,
            1_200 to 240,
            400 to 120,
        )
        for ((outputLimit, promptLimit) in textTiers) {
            candidate = candidate.copy(
                output = candidate.output.truncateWithEllipsis(outputLimit),
                prompt = candidate.prompt.truncateWithEllipsis(promptLimit),
            )
            if (candidate.encode().length <= budget) return candidate
        }

        // Level 2: keep details of the latest steps first, compressing early step details.
        if (candidate.steps.isNotEmpty()) {
            val detailTiers = listOf(
                Triple(240, 800, 160),
                Triple(0, 400, 120),
                Triple(0, 120, 96),
                Triple(0, 0, 80),
            )
            for ((olderDetailLimit, recentDetailLimit, titleLimit) in detailTiers) {
                val recentStart = (candidate.steps.size - RECENT_STEPS_PRIORITY_COUNT).coerceAtLeast(0)
                candidate = candidate.copy(
                    steps = candidate.steps.mapIndexed { index, step ->
                        val detailLimit = if (index >= recentStart) recentDetailLimit else olderDetailLimit
                        step.copy(
                            id = step.id.takeUtf16Safe(MAX_ID_CHARS),
                            title = step.title.truncateWithEllipsis(titleLimit),
                            detail = step.detail.truncateWithEllipsis(detailLimit),
                            toolName = step.toolName.takeUtf16Safe(MAX_TYPE_CHARS),
                        )
                    },
                )
                if (candidate.encode().length <= budget) return candidate
            }
        }

        // Level 3: drop the earliest steps, accumulate omittedSteps, keep the latest steps and total tool-step count.
        if (candidate.steps.isNotEmpty()) {
            val allSteps = candidate.steps
            val baseOmitted = candidate.omittedSteps
            var dropCount = 1
            while (dropCount <= allSteps.size) {
                val remaining = allSteps.subList(dropCount, allSteps.size)
                val shrunk = candidate.copy(
                    steps = remaining,
                    omittedSteps = baseOmitted + dropCount,
                )
                if (shrunk.encode().length <= budget) return shrunk
                // Accelerate the step size when there are many steps, finally ensuring it can reach clearing all steps.
                val step = ((allSteps.size - dropCount) / 3).coerceAtLeast(1)
                dropCount = if (dropCount == allSteps.size) {
                    allSteps.size + 1
                } else {
                    (dropCount + step).coerceAtMost(allSteps.size)
                }
            }
            candidate = candidate.copy(
                steps = emptyList(),
                omittedSteps = baseOmitted + allSteps.size,
            )
            if (candidate.encode().length <= budget) return candidate
        }

        // Level 4: fall back to extreme escaping of metadata, clear the body, and progressively tighten each metadata length to ensure it never exceeds the limit.
        candidate = candidate.copy(
            prompt = "",
            output = "",
            steps = emptyList(),
        )
        var fieldLimit = 120
        while (candidate.encode().length > budget && fieldLimit > 0) {
            candidate = candidate.copy(
                subagentId = candidate.subagentId.takeUtf16Safe(fieldLimit.coerceAtMost(MAX_ID_CHARS)),
                subagentType = candidate.subagentType.takeUtf16Safe(fieldLimit.coerceAtMost(MAX_TYPE_CHARS)),
                description = candidate.description.truncateWithEllipsis(fieldLimit),
                model = candidate.model.takeUtf16Safe(fieldLimit),
                currentAction = candidate.currentAction.truncateWithEllipsis(fieldLimit),
                error = candidate.error?.truncateWithEllipsis(fieldLimit),
            )
            if (candidate.encode().length <= budget) return candidate
            fieldLimit /= 2
        }
        if (candidate.encode().length > budget) {
            candidate = SubagentActivitySnapshot(
                subagentId = candidate.subagentId.takeUtf16Safe(24),
                subagentType = candidate.subagentType.takeUtf16Safe(24).ifBlank { SubagentProfile.DEFAULT_TYPE },
                status = candidate.status,
                usage = candidate.usage,
                totalToolSteps = preservedToolSteps,
                omittedSteps = candidate.omittedSteps,
            )
        }
        return candidate
    }

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        /** Default wire serialization limit, leaving enough margin to avoid hitting the IPC's common 16,000-character clipping threshold. */
        const val WIRE_BUDGET_CHARS = 12_000

        private const val MIN_WIRE_BUDGET_CHARS = 512
        private const val RECENT_STEPS_PRIORITY_COUNT = 4
        private const val MAX_ID_CHARS = 64
        private const val MAX_TYPE_CHARS = 48
        private const val MAX_DESCRIPTION_CHARS = 120
        private const val MAX_PROMPT_CHARS = 4_000
        private const val MAX_MODEL_CHARS = 120
        private const val MAX_ACTION_CHARS = 120
        private const val MAX_OUTPUT_CHARS = 12_000
        private const val MAX_ERROR_CHARS = 1_000

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(raw: String?): SubagentActivitySnapshot? {
            if (raw.isNullOrBlank() || !raw.trimStart().startsWith("{")) return null
            return runCatching { json.decodeFromString<SubagentActivitySnapshot>(raw) }
                .getOrNull()
                ?.takeIf { it.subagentType.isNotBlank() || it.description.isNotBlank() || it.prompt.isNotBlank() }
        }

        /**
         * Wire-level fallback: when the snapshot JSON exceeds [maxChars], re-encode it by the structured budget to avoid hard truncation breaking the JSON;
         * if the original string isn't a valid snapshot to begin with, return null and let the caller use a generic strategy.
         */
        fun sanitizeForWire(raw: String?, maxChars: Int = WIRE_BUDGET_CHARS): String? {
            if (raw == null) return null
            if (raw.length <= maxChars) return raw
            return decode(raw)?.encodeForWire(maxChars)
        }

        fun initialFromArguments(argumentsJson: String): SubagentActivitySnapshot? = runCatching {
            val args = JSONObject(argumentsJson)
            val resumeId = args.optString("resume").trim().takeUtf16Safe(MAX_ID_CHARS)
            val rawType = args.optString("subagent_type").trim().lowercase()
            val type = when {
                rawType.isNotEmpty() -> rawType.takeUtf16Safe(MAX_TYPE_CHARS)
                resumeId.isNotEmpty() -> "subagent"
                else -> SubagentProfile.DEFAULT_TYPE
            }
            val description = args.optString("description").trim()
                .replace(Regex("\\s+"), " ")
                .truncateWithEllipsis(80)
            val prompt = args.optString("prompt").trim().truncateWithEllipsis(MAX_PROMPT_CHARS)
            SubagentActivitySnapshot(
                subagentId = resumeId,
                subagentType = type,
                description = description,
                prompt = prompt,
                resumed = resumeId.isNotEmpty(),
                status = STATUS_RUNNING,
                currentAction = "Starting…",
            )
        }.getOrNull()
    }
}

/** Safely truncates the prefix by UTF-16 units, never splitting a surrogate pair. */
internal fun String.takeUtf16Safe(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    val end = if (Character.isHighSurrogate(this[maxChars - 1])) maxChars - 1 else maxChars
    return if (end <= 0) "" else substring(0, end)
}

/** Safely truncates the suffix by UTF-16 units, never splitting a surrogate pair. */
internal fun String.takeLastUtf16Safe(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    val start = length - maxChars
    val safeStart = if (Character.isLowSurrogate(this[start])) start + 1 else start
    return if (safeStart >= length) "" else substring(safeStart)
}

internal fun String.truncateWithEllipsis(maxChars: Int): String {
    if (maxChars <= 0) return ""
    if (length <= maxChars) return this
    if (maxChars == 1) return takeUtf16Safe(1)
    return takeUtf16Safe(maxChars - 1) + "…"
}
