package io.github.mangi.eta.ui.screens.scheduled

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import io.github.mangi.eta.data.model.ScheduledTaskDeliveryMode
import io.github.mangi.eta.data.model.ScheduledTaskRunStatus
import io.github.mangi.eta.data.model.ScheduledTaskSessionMode
import io.github.mangi.eta.data.model.ScheduledTaskTriggerSource
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusIdle
import io.github.mangi.eta.ui.components.StatusRunning
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object ScheduledTaskUiFormatting {
    private val TIME_ONLY_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun deliveryBadge(mode: ScheduledTaskDeliveryMode): String = when (mode) {
        ScheduledTaskDeliveryMode.ALWAYS ->
            AppStrings.get(R.string.scheduled_delivery_badge_always, "Every notification")
        ScheduledTaskDeliveryMode.ON_CHANGE ->
            AppStrings.get(R.string.scheduled_delivery_badge_on_change, "Only on change")
        ScheduledTaskDeliveryMode.SILENT ->
            AppStrings.get(R.string.scheduled_delivery_badge_silent, "Silent archive")
    }

    fun sessionModeLabel(mode: ScheduledTaskSessionMode): String = when (mode) {
        ScheduledTaskSessionMode.ISOLATED ->
            AppStrings.get(R.string.scheduled_session_mode_isolated_label, "Archive independently each time (no main chat refresh)")
        ScheduledTaskSessionMode.DEDICATED ->
            AppStrings.get(R.string.scheduled_session_mode_dedicated_label, "Fixed dedicated conversation channel")
    }

    fun statusLabel(status: ScheduledTaskRunStatus?, runningNow: Boolean): String {
        if (runningNow || status == ScheduledTaskRunStatus.RUNNING) {
            return AppStrings.get(R.string.scheduled_status_running, "Running")
        }
        return when (status) {
            ScheduledTaskRunStatus.DELIVERED ->
                AppStrings.get(R.string.scheduled_status_delivered, "Delivered")
            ScheduledTaskRunStatus.NO_CHANGE ->
                AppStrings.get(R.string.scheduled_status_no_change, "No change")
            ScheduledTaskRunStatus.SKIPPED ->
                AppStrings.get(R.string.scheduled_status_skipped, "Skipped")
            ScheduledTaskRunStatus.FAILED ->
                AppStrings.get(R.string.scheduled_status_failed, "Execution failed")
            ScheduledTaskRunStatus.TIMEOUT ->
                AppStrings.get(R.string.scheduled_status_timeout, "Timed out")
            ScheduledTaskRunStatus.CANCELLED ->
                AppStrings.get(R.string.scheduled_status_cancelled, "Stopped")
            ScheduledTaskRunStatus.RUNNING ->
                AppStrings.get(R.string.scheduled_status_running, "Running")
            null ->
                AppStrings.get(R.string.scheduled_status_never_run, "Not run yet")
        }
    }

    fun triggerSourceLabel(source: ScheduledTaskTriggerSource): String = when (source) {
        ScheduledTaskTriggerSource.SCHEDULE ->
            AppStrings.get(R.string.scheduled_trigger_source_schedule, "Scheduled")
        ScheduledTaskTriggerSource.MANUAL ->
            AppStrings.get(R.string.scheduled_trigger_source_manual, "Manual test run")
        ScheduledTaskTriggerSource.MISFIRE_CATCHUP ->
            AppStrings.get(R.string.scheduled_trigger_source_misfire_catchup, "Delayed catch-up")
    }

    fun formatTimestamp(epochMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs == null || epochMs <= 0L) return ""
        val zone = ZoneId.systemDefault()
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val target = Instant.ofEpochMilli(epochMs).atZone(zone)
        val hhmm = target.format(TIME_ONLY_FORMATTER)
        return when {
            target.toLocalDate() == nowDate ->
                AppStrings.get(R.string.scheduled_timestamp_today, "Today %1\$s", hhmm)
            target.toLocalDate() == nowDate.minusDays(1) ->
                AppStrings.get(R.string.scheduled_timestamp_yesterday, "Yesterday %1\$s", hhmm)
            else ->
                AppStrings.get(
                    R.string.scheduled_date_time_short,
                    "%1\$d/%2\$d %3\$s",
                    target.monthValue,
                    target.dayOfMonth,
                    hhmm,
                )
        }
    }

    fun formatDuration(durationMs: Long?): String {
        if (durationMs == null || durationMs < 0L) return ""
        val seconds = durationMs / 1000L
        return when {
            seconds < 1L -> AppStrings.get(R.string.scheduled_duration_under_1s, "<1 s")
            seconds < 60L -> AppStrings.get(R.string.scheduled_duration_seconds, "%1\$d s", seconds)
            else -> AppStrings.get(
                R.string.scheduled_duration_minutes_seconds,
                "%1\$d m %2\$d s",
                seconds / 60,
                seconds % 60,
            )
        }
    }

    @Composable
    fun statusDotColor(status: ScheduledTaskRunStatus?, runningNow: Boolean, autoDisabled: Boolean): Color = when {
        runningNow || status == ScheduledTaskRunStatus.RUNNING -> StatusRunning
        autoDisabled || status == ScheduledTaskRunStatus.FAILED || status == ScheduledTaskRunStatus.TIMEOUT -> StatusError
        status == ScheduledTaskRunStatus.DELIVERED -> StatusSuccess
        status == ScheduledTaskRunStatus.SKIPPED -> StatusWarning
        else -> StatusIdle
    }
}
