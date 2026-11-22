package io.github.namanwtf.atom.agent.scheduler

import java.util.concurrent.ConcurrentHashMap

/**
 * Structured delivery intent reported by the `scheduled_task_report` tool during a scheduled-task run.
 *
 * Decoupled from the final conversation body: the model can output the full report body for archival reading while explicitly declaring this run to be either
 * `deliver` (with a concise notification summary) or `no_change` (silently skip the notification).
 */
internal data class ScheduledTaskReportedOutcome(
    val outcome: Outcome,
    val summary: String,
) {
    enum class Outcome { DELIVER, NO_CHANGE }
}

internal object ScheduledTaskRunReportRegistry {
    private val reports = ConcurrentHashMap<String, ScheduledTaskReportedOutcome>()

    fun record(runId: String, outcome: ScheduledTaskReportedOutcome) {
        if (runId.isBlank()) return
        reports[runId] = outcome
    }

    fun consume(runId: String): ScheduledTaskReportedOutcome? =
        if (runId.isBlank()) null else reports.remove(runId)

    fun clear(runId: String) {
        if (runId.isNotBlank()) reports.remove(runId)
    }
}
