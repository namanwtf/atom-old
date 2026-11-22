package io.github.namanwtf.atom.agent.scheduler

import io.github.namanwtf.atom.data.model.ScheduledTask
import io.github.namanwtf.atom.data.model.ScheduledTaskCapabilities
import io.github.namanwtf.atom.data.model.ScheduledTaskDeliveryMode
import io.github.namanwtf.atom.data.model.ScheduledTaskSessionMode
import io.github.namanwtf.atom.data.model.ScheduledTaskTriggerSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * Controlled run context passed to Runtime for a scheduled task (embedded in `AgentExternalArchivePayload.adapterPayload`).
 *
 * Keeps the `AgentRuntimeWire` protocol fully backward compatible without adding breaking top-level fields.
 */
internal data class ScheduledTaskRunContext(
    val taskId: String,
    val taskName: String,
    val runId: String,
    val triggerSource: ScheduledTaskTriggerSource,
    val deliveryMode: ScheduledTaskDeliveryMode,
    val showRunningNotification: Boolean = false,
    val sessionMode: ScheduledTaskSessionMode,
    val capabilities: ScheduledTaskCapabilities,
    val memo: String,
    val scheduledForAt: Long?,
    val startedAt: Long,
) {
    fun toAdapterPayload(): JSONObject = JSONObject()
        .put(KEY_MARKER, true)
        .put(KEY_TASK_ID, taskId)
        .put(KEY_TASK_NAME, taskName)
        .put(KEY_RUN_ID, runId)
        .put(KEY_TRIGGER_SOURCE, triggerSource.wireValue)
        .put(KEY_DELIVERY_MODE, deliveryMode.wireValue)
        .put(KEY_SHOW_RUNNING_NOTIFICATION, showRunningNotification)
        .put(KEY_SESSION_MODE, sessionMode.wireValue)
        .put(KEY_CAP_BROWSER, capabilities.browserTools)
        .put(KEY_CAP_DEVICE_READ, capabilities.deviceReadTools)
        .put(KEY_CAP_DEVICE_ACTION, capabilities.deviceActionTools)
        .put(KEY_CAP_TERMINAL, capabilities.terminalTools)
        .put(KEY_CAP_MCP, capabilities.mcpTools)
        .put(KEY_CAP_GUI, capabilities.guiTools)
        .put(KEY_MEMO, memo.take(ScheduledTask.MAX_MEMO_CHARS))
        .put(KEY_STARTED_AT, startedAt)
        .also { json ->
            scheduledForAt?.let { json.put(KEY_SCHEDULED_FOR_AT, it) }
        }

    /** Builds the unattended run contract and task notepad injected into the model system prompt. */
    fun buildSystemGuidance(zone: ZoneId = ZoneId.systemDefault()): String {
        val nowText = Instant.ofEpochMilli(startedAt).atZone(zone).format(DATETIME_FORMATTER)
        val triggerDesc = when (triggerSource) {
            ScheduledTaskTriggerSource.SCHEDULE -> "Triggered automatically on schedule"
            ScheduledTaskTriggerSource.MANUAL -> "Triggered by the user manually tapping \"Run now\""
            ScheduledTaskTriggerSource.MISFIRE_CATCHUP -> {
                val scheduledText = scheduledForAt?.let {
                    Instant.ofEpochMilli(it).atZone(zone).format(DATETIME_FORMATTER)
                }.orEmpty()
                "The device was previously asleep/offline, this is a delayed catch-up run(originally scheduled: $scheduledText)"
            }
        }
        val deliveryDesc = when (deliveryMode) {
            ScheduledTaskDeliveryMode.ALWAYS ->
                "This task uses \"notify after every completion\": please give a concise, clear final result with the key points first."
            ScheduledTaskDeliveryMode.ON_CHANGE ->
                "This task uses \"notify only when a change is found\": if there is no change or new development worth attention from the user compared to the last record in the task memo, " +
                    "please call scheduled_task_report(outcome=\"no_change\", summary=\"brief note that nothing changed\") and end the reply; the system will stay silent and not disturb the user; " +
                    "If you find a material change or something that needs a reminder, first update the task memo as needed, then call scheduled_task_report(outcome=\"deliver\", summary=\"1-2 sentence notification summary\") and output the full conclusion."
            ScheduledTaskDeliveryMode.SILENT ->
                "This task uses \"silent archive\": only complete the work and record it in the background; the system will not send a regular completion notification."
        }
        return buildString {
            append("[Unattended scheduled task mode]\n")
            append("Currently running the user preset scheduled task \"").append(taskName).append("\" (trigger type: ").append(triggerDesc).append("; current local time: ").append(nowText).append(").\n")
            append("Execution rules: \n")
            append("1. This is an unattended run, no one is at the screen to answer follow-ups. Deliver the final conclusion directly; do not just give a plan, do not say \"OK I will look into it\", do not ask the user for clarification; if a key condition or permission is truly missing, briefly state the blocking reason.\n")
            append("2. ").append(deliveryDesc).append('\n')
            append("3. You can use scheduled_task_update_memo to update the private memo of this task(to save the last check cursor, already announced items, or key state for comparison on the next scheduled run; never write secrets or sensitive credentials).\n")
            if (!capabilities.guiTools) {
                append("4. This task has no app UI(GUI)access enabled, please use only the currently available background tools to complete the task.\n")
            }
            if (memo.isNotBlank()) {
                append("\n<scheduled_task_memo>\n")
                append(memo.take(ScheduledTask.MAX_MEMO_CHARS))
                append("\n</scheduled_task_memo>")
            }
        }
    }

    companion object {
        private const val KEY_MARKER = "scheduled_task"
        private const val KEY_TASK_ID = "task_id"
        private const val KEY_TASK_NAME = "task_name"
        private const val KEY_RUN_ID = "run_id"
        private const val KEY_TRIGGER_SOURCE = "trigger_source"
        private const val KEY_DELIVERY_MODE = "delivery_mode"
        private const val KEY_SHOW_RUNNING_NOTIFICATION = "show_running_notification"
        private const val KEY_SESSION_MODE = "session_mode"
        private const val KEY_CAP_BROWSER = "cap_browser"
        private const val KEY_CAP_DEVICE_READ = "cap_device_read"
        private const val KEY_CAP_DEVICE_ACTION = "cap_device_action"
        private const val KEY_CAP_TERMINAL = "cap_terminal"
        private const val KEY_CAP_MCP = "cap_mcp"
        private const val KEY_CAP_GUI = "cap_gui"
        private const val KEY_MEMO = "memo"
        private const val KEY_SCHEDULED_FOR_AT = "scheduled_for_at"
        private const val KEY_STARTED_AT = "started_at"

        private val DATETIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEE)", Locale.ENGLISH)

        fun fromAdapterPayload(json: JSONObject?): ScheduledTaskRunContext? {
            if (json == null || !json.optBoolean(KEY_MARKER, false)) return null
            val taskId = json.optString(KEY_TASK_ID).trim()
            val runId = json.optString(KEY_RUN_ID).trim()
            if (taskId.isEmpty() || runId.isEmpty()) return null
            return ScheduledTaskRunContext(
                taskId = taskId,
                taskName = json.optString(KEY_TASK_NAME),
                runId = runId,
                triggerSource = ScheduledTaskTriggerSource.fromWireValue(json.optString(KEY_TRIGGER_SOURCE)),
                deliveryMode = ScheduledTaskDeliveryMode.fromWireValue(json.optString(KEY_DELIVERY_MODE)),
                showRunningNotification = json.optBoolean(KEY_SHOW_RUNNING_NOTIFICATION, false),
                sessionMode = ScheduledTaskSessionMode.fromWireValue(json.optString(KEY_SESSION_MODE)),
                capabilities = ScheduledTaskCapabilities(
                    browserTools = json.optBoolean(KEY_CAP_BROWSER, true),
                    deviceReadTools = json.optBoolean(KEY_CAP_DEVICE_READ, true),
                    deviceActionTools = json.optBoolean(KEY_CAP_DEVICE_ACTION, false),
                    terminalTools = json.optBoolean(KEY_CAP_TERMINAL, true),
                    mcpTools = json.optBoolean(KEY_CAP_MCP, true),
                    guiTools = json.optBoolean(KEY_CAP_GUI, false),
                ),
                memo = json.optString(KEY_MEMO),
                scheduledForAt = if (json.has(KEY_SCHEDULED_FOR_AT) && !json.isNull(KEY_SCHEDULED_FOR_AT)) {
                    json.optLong(KEY_SCHEDULED_FOR_AT)
                } else null,
                startedAt = json.optLong(KEY_STARTED_AT, System.currentTimeMillis()),
            )
        }
    }
}
