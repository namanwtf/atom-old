package io.github.mangi.eta.agent.scheduler

import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * 定时任务相关的模型工具 Schema。
 *
 * - 常规对话中暴露 [TOOL_MANAGE]（`scheduled_tasks`），支持自然语言创建、查看、开关、修改、删除与立即试跑；
 * - 无人值守定时任务执行期间隐藏创建能力以防递归自繁殖，仅暴露自作用域的 [TOOL_UPDATE_MEMO] 与 [TOOL_REPORT]。
 */
internal object ScheduledTaskToolCatalog {
    const val TOOL_MANAGE = "scheduled_tasks"
    const val TOOL_UPDATE_MEMO = "scheduled_task_update_memo"
    const val TOOL_REPORT = "scheduled_task_report"

    val manageNames = setOf(TOOL_MANAGE)
    val selfRunNames = setOf(TOOL_UPDATE_MEMO, TOOL_REPORT)

    fun appendManageTool(tools: JSONArray) {
        tools.put(manageToolSchema())
    }

    fun appendSelfRunTools(tools: JSONArray) {
        tools.put(updateMemoToolSchema())
        tools.put(reportToolSchema())
    }

    private fun manageToolSchema(): JSONObject {
        val properties = JSONObject()
            .put(
                "action",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("list", "create", "update", "delete", "run_now")))
                    .put(
                        "description",
                        "Action type：list lists all scheduled tasks；create creates a new scheduled task；update modifies an existing task or toggles it；delete removes a task；run_now runs it once immediately。",
                    ),
            )
            .put(
                "task_id",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 128)
                    .put("description", "Target task ID（required for update / delete / run_now）。"),
            )
            .put(
                "name",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 80)
                    .put("description", "A short, clear task name（e.g. “daily evening briefing”、“product restock monitoring”）。"),
            )
            .put(
                "prompt",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 8000)
                    .put("description", "The complete self-contained instruction for background Eta to run on each trigger；state the goal, data source and delivery format clearly。"),
            )
            .put(
                "trigger_kind",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("once", "daily", "weekdays", "weekly", "interval", "cron")))
                    .put("description", "Trigger frequency：once one-time、daily every day、weekdays workdays、weekly by weekday、interval fixed interval、cron custom 5-field expression。"),
            )
            .put(
                "hour",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 0)
                    .put("maximum", 23)
                    .put("description", "Local hour（0-23），used for daily / weekdays / weekly。"),
            )
            .put(
                "minute",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 0)
                    .put("maximum", 59)
                    .put("description", "Local minute（0-59），used for daily / weekdays / weekly。"),
            )
            .put(
                "days_of_week",
                JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 7))
                    .put("description", "List of repeat weekdays（1=Mon .. 7=Sun），used for weekly。"),
            )
            .put(
                "interval_minutes",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", ScheduledTaskScheduleCalculator.MIN_INTERVAL_MINUTES)
                    .put("maximum", ScheduledTaskScheduleCalculator.MAX_INTERVAL_MINUTES)
                    .put("description", "Fixed interval in minutes（minimum 5 minutes），used for interval。"),
            )
            .put(
                "delay_minutes",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 1)
                    .put("maximum", 43_200)
                    .put("description", "Relative delay in minutes for a one-time task（e.g. 60 means 1 hour later），used for once。"),
            )
            .put(
                "trigger_at",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 64)
                    .put("description", "Absolute trigger time for a one-time task（ISO-8601 or local yyyy-MM-dd HH:mm），used for once。"),
            )
            .put(
                "cron",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 80)
                    .put("description", "Standard 5-field Cron expression（minute hour day month weekday，e.g. '0 9 * * 1-5'），used for cron。"),
            )
            .put(
                "delivery_mode",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("always", "on_change", "silent")))
                    .put("description", "Notification mode：always notify after each completion（morning briefing/reminder）；on_change notify only when a change is found（monitoring/inspection）；silent archive without notifying。"),
            )
            .put(
                "show_running_notification",
                JSONObject()
                    .put("type", "boolean")
                    .put("description", "Whether to show a foreground progress notification and status bar pill while the task runs；default false（run silently in the background and only send a result notification per delivery_mode after completion）。"),
            )
            .put(
                "session_mode",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("isolated", "dedicated")))
                    .put("description", "Session ownership：isolated archives each run independently without refreshing the main chat（default）；dedicated places it into a fixed dedicated conversation channel。"),
            )
            .put(
                "enabled",
                JSONObject()
                    .put("type", "boolean")
                    .put("description", "Whether to enable this scheduled task（create defaults to true；update can pause or resume）。"),
            )
            .put(
                "allow_gui",
                JSONObject()
                    .put("type", "boolean")
                    .put("description", "Whether to allow background interaction with third-party app UIs（GUI）；defaults to false to ensure silent, safe execution while the screen is locked。"),
            )
            .put(
                "timeout_seconds",
                JSONObject()
                    .put("type", "integer")
                    .put("minimum", 30)
                    .put("maximum", 1800)
                    .put("description", "Wall-clock timeout in seconds for a single run（default 300）。"),
            )

        return AgentToolSchema.function(
            name = TOOL_MANAGE,
            description = "Manage the Eta scheduled tasks on this device：list, create, modify, toggle, delete, or immediately trial-run a scheduled task。" +
                "Use this tool when the user asks to schedule/daily/weekly/every so often/auto-run some work in a few minutes or at a specific time，or asks to turn the work just completed into a scheduled task。" +
                "If the time is unclear before creating，briefly confirm first；after creating or modifying，explain the schedule, notification mode, and next run time in plain language。",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(listOf("action"))),
        )
    }

    private fun updateMemoToolSchema(): JSONObject {
        val properties = JSONObject()
            .put(
                "content",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 16_000)
                    .put("description", "The full text to save as the latest private memo of this task（e.g. the last check timestamp, latest entry ID, or price baseline；pass an empty string to clear）。"),
            )
        return AgentToolSchema.function(
            name = TOOL_UPDATE_MEMO,
            description = "Update the private memo（Task Memo）of the current scheduled task to persist this run's cursor, status, or announced summary for comparison on the next scheduled run to avoid duplicate reminders. Never write secrets or sensitive credentials。",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(listOf("content"))),
        )
    }

    private fun reportToolSchema(): JSONObject {
        val properties = JSONObject()
            .put(
                "outcome",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("deliver", "no_change")))
                    .put("description", "deliver means there is a result to notify the user about；no_change means the inspection found no noteworthy change，and the system will stay silent and not send a notification。"),
            )
            .put(
                "summary",
                JSONObject()
                    .put("type", "string")
                    .put("maxLength", 300)
                    .put("description", "A concise 1-2 sentence summary of the conclusion，used for the notification preview and the run timeline display。"),
            )
        return AgentToolSchema.function(
            name = TOOL_REPORT,
            description = "Report the structured delivery conclusion of this run to the scheduled task scheduler：in 「notify on change only」 mode, when there is no change be sure to pass outcome=\"no_change\" to stay silent；when the user needs to be notified, pass outcome=\"deliver\" and provide a concise notification summary。",
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(listOf("outcome", "summary"))),
        )
    }
}
