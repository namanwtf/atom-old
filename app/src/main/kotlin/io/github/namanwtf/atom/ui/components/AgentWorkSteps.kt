package io.github.namanwtf.atom.ui.components

import androidx.compose.runtime.Immutable
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.core.AppStrings
import io.github.namanwtf.atom.ui.model.AgentChatMessageUi
import io.github.namanwtf.atom.ui.model.ThinkingMessageUi
import io.github.namanwtf.atom.ui.model.ToolActivityMessageUi
import io.github.namanwtf.atom.ui.model.ToolActivityStatusUi

/** One timeline node inside the work-process card: a single message, or a group formed by consecutive calls to the same tool. */
@Immutable
internal sealed interface AgentWorkStep {
    val key: String

    data class Single(val message: AgentChatMessageUi) : AgentWorkStep {
        override val key: String = message.id
    }

    data class ToolGroup(
        val toolName: String,
        val calls: List<ToolActivityMessageUi>,
    ) : AgentWorkStep {
        override val key: String = "group-${calls.first().id}"
        val running: Boolean get() = calls.any { it.status == ToolActivityStatusUi.Running }
        val failedCount: Int get() = calls.count { it.status == ToolActivityStatusUi.Failed }
    }
}

/**
 * The Agent often reads multiple files or observes the screen repeatedly; consecutive same-name tool calls merge into one node,
 * so the timeline keeps only the "what was done" level and details expand on demand. Browser entries carry a live preview and stay separate.
 */
internal fun List<AgentChatMessageUi>.toWorkSteps(): List<AgentWorkStep> = buildList {
    val run = mutableListOf<ToolActivityMessageUi>()

    fun flushRun() {
        when (run.size) {
            0 -> Unit
            1 -> add(AgentWorkStep.Single(run.single()))
            else -> add(AgentWorkStep.ToolGroup(toolName = run.first().toolName, calls = run.toList()))
        }
        run.clear()
    }

    this@toWorkSteps.forEach { message ->
        if (message is ToolActivityMessageUi && message.toolName !in UNGROUPED_TOOLS) {
            if (run.isNotEmpty() && run.first().toolName != message.toolName) flushRun()
            run += message
        } else {
            flushRun()
            add(AgentWorkStep.Single(message))
        }
    }
    flushRun()
}

private val UNGROUPED_TOOLS = setOf("browser_use", "subagent")

/** Counts needed for the collapsed work-process summary. */
internal data class AgentWorkSummary(
    val thinkingCount: Int,
    val toolCount: Int,
    val failedCount: Int,
)

internal fun List<AgentChatMessageUi>.workSummary(): AgentWorkSummary = AgentWorkSummary(
    thinkingCount = count { it is ThinkingMessageUi },
    toolCount = count { it is ToolActivityMessageUi },
    failedCount = count { it is ToolActivityMessageUi && it.status == ToolActivityStatusUi.Failed },
)

/**
 * One result line under the collapsed row. A successful ordinary result (like "done") takes no line; terminal output takes the last line,
 * because a command's conclusion is usually at the end; failures drop the repeated "failed" prefix and the code= tail used for logs.
 */
internal fun ToolActivityMessageUi.outcomeLine(): String? {
    val summary = resultSummary?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val lines = summary.lines().map(String::trim).filter(String::isNotEmpty)
    val head = lines.firstOrNull() ?: return null
    return when (status) {
        ToolActivityStatusUi.Running -> null
        ToolActivityStatusUi.Failed, ToolActivityStatusUi.Unknown -> {
            val detail = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            stripAnyPrefix(head, failurePrefixes())
                .substringBefore(FAILURE_CODE_SEPARATOR)
                .takeIf { it.isNotBlank() && it !in failureLabels() }
                ?: detail
        }
        ToolActivityStatusUi.Success -> {
            val output = lines.drop(1).lastOrNull { it != TRUNCATION_MARK }
            when {
                output != null -> output
                head in trivialSuccessLabels() -> null
                else -> stripAnyPrefix(head, successPrefixes()).takeIf { it.isNotBlank() }
            }
        }
    }
}

private fun stripAnyPrefix(text: String, prefixes: List<String>): String {
    for (prefix in prefixes) {
        if (text.startsWith(prefix)) return text.removePrefix(prefix)
    }
    return text
}

private fun failureLabels(): Set<String> = setOf(
    "failed",
    "failed",
    "Failed",
    AppStrings.get(R.string.workstep_failure_label, "failed"),
)

private fun failurePrefixes(): List<String> = failureLabels().map { "$it · " }

private fun trivialSuccessLabels(): Set<String> = setOf(
    "done",
    "completed",
    "completed",
    "Done",
    "Completed",
    AppStrings.get(R.string.workstep_success_label, "done"),
    AppStrings.get(R.string.workstep_exec_done_label, "completed"),
)

private fun successPrefixes(): List<String> = listOf(
    "done · ",
    "Done · ",
    "Completed · ",
    "${AppStrings.get(R.string.workstep_success_label, "done")} · ",
).distinct()

// Matches the fixed format of the Runtime-side result summary, while staying compatible with historical multi-language persisted records.
private const val FAILURE_CODE_SEPARATOR = " · code="
private const val TRUNCATION_MARK = "…"
