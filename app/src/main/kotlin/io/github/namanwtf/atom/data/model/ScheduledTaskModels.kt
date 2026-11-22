package io.github.namanwtf.atom.data.model

import androidx.annotation.StringRes
import io.github.namanwtf.atom.R
import kotlinx.serialization.Serializable

/**
 * Scheduled task trigger type.
 *
 * Decoupled from the task execution body, making it easy to extend event triggers later without changing the execution and notification paths.
 */
@Serializable
enum class ScheduledTaskTriggerKind(val wireValue: String) {
    ONCE("once"),
    DAILY("daily"),
    WEEKDAYS("weekdays"),
    WEEKLY("weekly"),
    INTERVAL("interval"),
    CRON("cron");

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskTriggerKind =
            entries.firstOrNull { it.wireValue == raw } ?: DAILY
    }
}

/** How scheduled task results are delivered. */
@Serializable
enum class ScheduledTaskDeliveryMode(val wireValue: String) {
    /** Send a summary notification after each completion (morning digest, evening digest, reminders). */
    ALWAYS("always"),
    /** Send a notification only when a noteworthy change is found; archive silently when nothing changed (monitoring, inspection). */
    ON_CHANGE("on_change"),
    /** Execute and archive in the background only, without sending the usual completion notification (except failure alerts). */
    SILENT("silent");

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskDeliveryMode =
            entries.firstOrNull { it.wireValue == raw } ?: ALWAYS
    }
}

/** Conversation ownership mode for scheduled task results. */
@Serializable
enum class ScheduledTaskSessionMode(val wireValue: String) {
    /** Each run creates an independent archive without spamming the main chat list; tapping the notification or timeline can open it at any time to continue asking. */
    ISOLATED("isolated"),
    /** Fixed to the task's dedicated conversation channel, appended chronologically on each run. */
    DEDICATED("dedicated");

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskSessionMode =
            entries.firstOrNull { it.wireValue == raw } ?: ISOLATED
    }
}

/** Handling strategy when the device powered off or slept past a trigger point. */
@Serializable
enum class ScheduledTaskMisfirePolicy(val wireValue: String) {
    /** Within the expiry window, catch up at most the most recent run; skip when beyond the window (to avoid a morning digest firing in the middle of the night). */
    RUN_ONCE_WITHIN_WINDOW("run_once_within_window"),
    /** Skip when missed and wait for the next scheduled point. */
    SKIP("skip");

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskMisfirePolicy =
            entries.firstOrNull { it.wireValue == raw } ?: RUN_ONCE_WITHIN_WINDOW
    }
}

/** Task trigger scheduling parameters. */
@Serializable
data class ScheduledTaskSchedule(
    val kind: ScheduledTaskTriggerKind = ScheduledTaskTriggerKind.DAILY,
    /** Absolute trigger timestamp (milliseconds) for one-shot tasks. */
    val triggerAtEpochMs: Long? = null,
    /** Local hour (0..23), used for DAILY / WEEKDAYS / WEEKLY. */
    val hour: Int = 9,
    /** Local minute (0..59), used for DAILY / WEEKDAYS / WEEKLY. */
    val minute: Int = 0,
    /** ISO-8601 day-of-week set (1=Monday .. 7=Sunday), used for WEEKLY. */
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** Fixed interval in minutes (minimum 5 minutes), used for INTERVAL. */
    val intervalMinutes: Int = 60,
    /** Standard 5-field Cron expression (minute hour dom month dow), used for CRON. */
    val cronExpression: String = "",
    /** IANA time zone ID; an empty string means follow the device's current local time zone. */
    val timezoneId: String = "",
)

/** Optional quiet (do-not-disturb) period; periodic triggers falling within this window are skipped. */
@Serializable
data class ScheduledTaskQuietHours(
    val enabled: Boolean = false,
    val startHour: Int = 23,
    val startMinute: Int = 0,
    val endHour: Int = 7,
    val endMinute: Int = 30,
)

/**
 * Per-task capability trimming switches for scheduled tasks.
 *
 * At runtime they are still intersected with the global switches; they can only narrow permissions and cannot bypass the user's master switch.
 * [guiTools] is disabled by default to keep background runs silent and safe while the phone is locked or the screen is off.
 */
@Serializable
data class ScheduledTaskCapabilities(
    val browserTools: Boolean = true,
    val deviceReadTools: Boolean = true,
    val deviceActionTools: Boolean = false,
    val terminalTools: Boolean = true,
    val mcpTools: Boolean = true,
    val guiTools: Boolean = false,
)

/** Terminal or in-progress state of a single scheduled task run. */
@Serializable
enum class ScheduledTaskRunStatus(val wireValue: String) {
    RUNNING("running"),
    DELIVERED("delivered"),
    NO_CHANGE("no_change"),
    SKIPPED("skipped"),
    FAILED("failed"),
    TIMEOUT("timeout"),
    CANCELLED("cancelled");

    val isTerminal: Boolean get() = this != RUNNING
    val isSuccess: Boolean get() = this == DELIVERED || this == NO_CHANGE
    val countsAsFailure: Boolean get() = this == FAILED || this == TIMEOUT

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskRunStatus =
            entries.firstOrNull { it.wireValue == raw } ?: FAILED
    }
}

/** Trigger source. */
@Serializable
enum class ScheduledTaskTriggerSource(val wireValue: String) {
    SCHEDULE("schedule"),
    MANUAL("manual"),
    MISFIRE_CATCHUP("misfire_catchup");

    companion object {
        fun fromWireValue(raw: String?): ScheduledTaskTriggerSource =
            entries.firstOrNull { it.wireValue == raw } ?: SCHEDULE
    }
}

/** Scheduled task domain model. */
data class ScheduledTask(
    val id: String,
    val name: String,
    val prompt: String,
    val enabled: Boolean = true,
    val schedule: ScheduledTaskSchedule = ScheduledTaskSchedule(),
    val misfirePolicy: ScheduledTaskMisfirePolicy = ScheduledTaskMisfirePolicy.RUN_ONCE_WITHIN_WINDOW,
    val misfireWindowMinutes: Int = DEFAULT_MISFIRE_WINDOW_MINUTES,
    val quietHours: ScheduledTaskQuietHours = ScheduledTaskQuietHours(),
    val deliveryMode: ScheduledTaskDeliveryMode = ScheduledTaskDeliveryMode.ALWAYS,
    /** Whether to show a foreground progress notification and status bar capsule while running; when disabled it runs silently if it qualifies for background exemption, pushing the result only on completion per [deliveryMode]. */
    val showRunningNotification: Boolean = false,
    val sessionMode: ScheduledTaskSessionMode = ScheduledTaskSessionMode.ISOLATED,
    val dedicatedConversationKey: String? = null,
    /** null means follow the global default model. */
    val modelId: String? = null,
    /** null means follow the global default reasoning settings. */
    val reasoningEffort: String? = null,
    val capabilities: ScheduledTaskCapabilities = ScheduledTaskCapabilities(),
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** Task-specific notepad (cursor / last-checked state) that prevents duplicate reporting and keeps context lean. */
    val memo: String = "",
    val memoUpdatedAt: Long = 0L,
    val nextRunAt: Long? = null,
    val lastRunAt: Long? = null,
    val lastStatus: ScheduledTaskRunStatus? = null,
    val lastSummary: String = "",
    val consecutiveFailures: Int = 0,
    val autoDisabledReason: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val resolvedReasoningEffort: ReasoningEffort?
        get() = reasoningEffort?.takeIf { it.isNotBlank() }?.let(ReasoningEffort::fromWireValue)

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 300
        const val MIN_TIMEOUT_SECONDS = 30
        const val MAX_TIMEOUT_SECONDS = 1800
        const val DEFAULT_MISFIRE_WINDOW_MINUTES = 180
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val MAX_MEMO_CHARS = 16_000
    }
}

/** Record of a single scheduled task run. */
data class ScheduledTaskRun(
    val id: String,
    val taskId: String,
    val triggerSource: ScheduledTaskTriggerSource,
    val scheduledForAt: Long?,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: ScheduledTaskRunStatus,
    val summary: String = "",
    val notified: Boolean = false,
    val archiveConversationKey: String? = null,
    val modelLabel: String = "",
) {
    val durationMs: Long?
        get() = finishedAt?.let { (it - startedAt).coerceAtLeast(0L) }
}

/** Curated inspiration templates for the empty state. */
data class ScheduledTaskPreset(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @StringRes val defaultNameRes: Int,
    @StringRes val promptRes: Int,
    val schedule: ScheduledTaskSchedule,
    val deliveryMode: ScheduledTaskDeliveryMode,
    val sessionMode: ScheduledTaskSessionMode,
    val capabilities: ScheduledTaskCapabilities,
)

internal object ScheduledTaskPresets {
    val all: List<ScheduledTaskPreset> = listOf(
        ScheduledTaskPreset(
            id = "evening_briefing",
            titleRes = R.string.scheduled_preset_evening_briefing_title,
            subtitleRes = R.string.scheduled_preset_evening_briefing_subtitle,
            defaultNameRes = R.string.scheduled_preset_evening_briefing_name,
            promptRes = R.string.scheduled_preset_evening_briefing_prompt,
            schedule = ScheduledTaskSchedule(
                kind = ScheduledTaskTriggerKind.DAILY,
                hour = 22,
                minute = 0,
            ),
            deliveryMode = ScheduledTaskDeliveryMode.ALWAYS,
            sessionMode = ScheduledTaskSessionMode.DEDICATED,
            capabilities = ScheduledTaskCapabilities(
                browserTools = true,
                deviceReadTools = true,
                deviceActionTools = false,
                terminalTools = false,
                mcpTools = false,
                guiTools = false,
            ),
        ),
        ScheduledTaskPreset(
            id = "morning_digest",
            titleRes = R.string.scheduled_preset_morning_digest_title,
            subtitleRes = R.string.scheduled_preset_morning_digest_subtitle,
            defaultNameRes = R.string.scheduled_preset_morning_digest_name,
            promptRes = R.string.scheduled_preset_morning_digest_prompt,
            schedule = ScheduledTaskSchedule(
                kind = ScheduledTaskTriggerKind.WEEKDAYS,
                hour = 8,
                minute = 0,
            ),
            deliveryMode = ScheduledTaskDeliveryMode.ALWAYS,
            sessionMode = ScheduledTaskSessionMode.DEDICATED,
            capabilities = ScheduledTaskCapabilities(
                browserTools = true,
                deviceReadTools = true,
                deviceActionTools = false,
                terminalTools = false,
                mcpTools = true,
                guiTools = false,
            ),
        ),
        ScheduledTaskPreset(
            id = "web_watcher",
            titleRes = R.string.scheduled_preset_web_watcher_title,
            subtitleRes = R.string.scheduled_preset_web_watcher_subtitle,
            defaultNameRes = R.string.scheduled_preset_web_watcher_name,
            promptRes = R.string.scheduled_preset_web_watcher_prompt,
            schedule = ScheduledTaskSchedule(
                kind = ScheduledTaskTriggerKind.INTERVAL,
                intervalMinutes = 120,
            ),
            deliveryMode = ScheduledTaskDeliveryMode.ON_CHANGE,
            sessionMode = ScheduledTaskSessionMode.ISOLATED,
            capabilities = ScheduledTaskCapabilities(
                browserTools = true,
                deviceReadTools = false,
                deviceActionTools = false,
                terminalTools = false,
                mcpTools = false,
                guiTools = false,
            ),
        ),
        ScheduledTaskPreset(
            id = "weekly_review",
            titleRes = R.string.scheduled_preset_weekly_review_title,
            subtitleRes = R.string.scheduled_preset_weekly_review_subtitle,
            defaultNameRes = R.string.scheduled_preset_weekly_review_name,
            promptRes = R.string.scheduled_preset_weekly_review_prompt,
            schedule = ScheduledTaskSchedule(
                kind = ScheduledTaskTriggerKind.WEEKLY,
                hour = 21,
                minute = 0,
                daysOfWeek = setOf(7),
            ),
            deliveryMode = ScheduledTaskDeliveryMode.ALWAYS,
            sessionMode = ScheduledTaskSessionMode.DEDICATED,
            capabilities = ScheduledTaskCapabilities(
                browserTools = false,
                deviceReadTools = true,
                deviceActionTools = false,
                terminalTools = true,
                mcpTools = false,
                guiTools = false,
            ),
        ),
    )

    fun find(id: String?): ScheduledTaskPreset? = id?.let { target -> all.firstOrNull { it.id == target } }
}
