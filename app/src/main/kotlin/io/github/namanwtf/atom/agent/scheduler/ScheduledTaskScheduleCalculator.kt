package io.github.namanwtf.atom.agent.scheduler

import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.core.AppStrings
import io.github.namanwtf.atom.data.model.ScheduledTask
import io.github.namanwtf.atom.data.model.ScheduledTaskMisfirePolicy
import io.github.namanwtf.atom.data.model.ScheduledTaskQuietHours
import io.github.namanwtf.atom.data.model.ScheduledTaskSchedule
import io.github.namanwtf.atom.data.model.ScheduledTaskTriggerKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure-function computation engine for scheduled-task scheduling, Cron parsing, quiet hours, and missed-run windows.
 *
 * All computations explicitly accept [nowMs] and [defaultZone] to stay deterministic across timezones, daylight-saving changes, and unit tests.
 */
internal object ScheduledTaskScheduleCalculator {
    const val MIN_INTERVAL_MINUTES = 5
    const val MAX_INTERVAL_MINUTES = 7 * 24 * 60
    private const val CRON_MAX_SEARCH_DAYS = 366 * 4

    data class CronExpression(
        val minutes: Set<Int>,
        val hours: Set<Int>,
        val daysOfMonth: Set<Int>,
        val months: Set<Int>,
        val daysOfWeek: Set<Int>,
        val domWildcard: Boolean,
        val dowWildcard: Boolean,
    ) {
        fun matches(time: ZonedDateTime): Boolean {
            if (time.minute !in minutes) return false
            if (time.hour !in hours) return false
            if (time.monthValue !in months) return false
            val domMatch = time.dayOfMonth in daysOfMonth
            // ISO DayOfWeek: 1=Mon..7=Sun -> Vixie cron: 0=Sun, 1=Mon..6=Sat
            val cronDow = if (time.dayOfWeek == DayOfWeek.SUNDAY) 0 else time.dayOfWeek.value
            val dowMatch = cronDow in daysOfWeek
            return when {
                domWildcard && dowWildcard -> true
                domWildcard -> dowMatch
                dowWildcard -> domMatch
                // Standard Vixie-cron semantics: when both dom and dow are non-wildcards, they form an OR relationship.
                else -> domMatch || dowMatch
            }
        }
    }

    sealed interface MisfireDecision {
        data object RunNow : MisfireDecision
        data object RunCatchUp : MisfireDecision
        data class Skip(val reason: String) : MisfireDecision
    }

    fun resolveZone(schedule: ScheduledTaskSchedule, defaultZone: ZoneId = ZoneId.systemDefault()): ZoneId {
        val raw = schedule.timezoneId.trim()
        if (raw.isEmpty()) return defaultZone
        return runCatching { ZoneId.of(raw) }.getOrElse { defaultZone }
    }

    /** Computes the next valid trigger time after [afterMs]; returns null when there is no subsequent trigger (e.g. an expired one-shot task or an invalid Cron). */
    fun computeNextRunAt(
        schedule: ScheduledTaskSchedule,
        afterMs: Long,
        quietHours: ScheduledTaskQuietHours = ScheduledTaskQuietHours(),
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val zone = resolveZone(schedule, defaultZone)
        return when (schedule.kind) {
            ScheduledTaskTriggerKind.ONCE -> {
                val target = schedule.triggerAtEpochMs ?: return null
                target.takeIf { it > afterMs }
            }
            ScheduledTaskTriggerKind.DAILY -> {
                val hour = schedule.hour.coerceIn(0, 23)
                val minute = schedule.minute.coerceIn(0, 59)
                findNextCalendarOccurrence(afterMs, zone, quietHours) { date ->
                    date.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
                }
            }
            ScheduledTaskTriggerKind.WEEKDAYS -> {
                val hour = schedule.hour.coerceIn(0, 23)
                val minute = schedule.minute.coerceIn(0, 59)
                val weekdays = setOf(1, 2, 3, 4, 5)
                findNextWeeklyOccurrence(afterMs, zone, hour, minute, weekdays, quietHours)
            }
            ScheduledTaskTriggerKind.WEEKLY -> {
                val hour = schedule.hour.coerceIn(0, 23)
                val minute = schedule.minute.coerceIn(0, 59)
                val days = schedule.daysOfWeek.filter { it in 1..7 }.toSet().ifEmpty { setOf(1) }
                findNextWeeklyOccurrence(afterMs, zone, hour, minute, days, quietHours)
            }
            ScheduledTaskTriggerKind.INTERVAL -> {
                val intervalMinutes = schedule.intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
                val stepMs = intervalMinutes * 60_000L
                var candidate = afterMs + stepMs
                // If it lands in quiet hours, advance by the step out of the quiet period to avoid pointless idling.
                if (quietHours.enabled) {
                    var guard = 0
                    while (guard < 288 && isInQuietHours(candidate, quietHours, zone)) {
                        candidate += stepMs
                        guard++
                    }
                    if (isInQuietHours(candidate, quietHours, zone)) return null
                }
                candidate
            }
            ScheduledTaskTriggerKind.CRON -> {
                val parsed = parseCron(schedule.cronExpression) ?: return null
                findNextCronOccurrence(parsed, afterMs, zone, quietHours)
            }
        }
    }

    /** Validates whether the schedule config is valid, returning a user-facing localized error message or null. */
    fun validateSchedule(
        schedule: ScheduledTaskSchedule,
        nowMs: Long,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        if (schedule.timezoneId.isNotBlank() && runCatching { ZoneId.of(schedule.timezoneId.trim()) }.isFailure) {
            return AppStrings.get(
                R.string.scheduled_error_invalid_timezone,
                "Invalid timezone identifier: %1\$s",
                schedule.timezoneId,
            )
        }
        return when (schedule.kind) {
            ScheduledTaskTriggerKind.ONCE -> {
                val target = schedule.triggerAtEpochMs
                when {
                    target == null -> AppStrings.get(
                        R.string.scheduled_error_once_time_required,
                        "Please set the trigger time for the one-time task",
                    )
                    target <= nowMs -> AppStrings.get(
                        R.string.scheduled_error_once_time_in_past,
                        "The one-time task trigger time must be later than the current time",
                    )
                    else -> null
                }
            }
            ScheduledTaskTriggerKind.DAILY,
            ScheduledTaskTriggerKind.WEEKDAYS -> {
                if (schedule.hour !in 0..23 || schedule.minute !in 0..59) {
                    AppStrings.get(R.string.scheduled_error_time_out_of_range, "Time is out of the valid range")
                } else null
            }
            ScheduledTaskTriggerKind.WEEKLY -> {
                when {
                    schedule.hour !in 0..23 || schedule.minute !in 0..59 ->
                        AppStrings.get(R.string.scheduled_error_time_out_of_range, "Time is out of the valid range")
                    schedule.daysOfWeek.none { it in 1..7 } ->
                        AppStrings.get(R.string.scheduled_error_weekly_days_required, "Please select at least one repeat weekday")
                    else -> null
                }
            }
            ScheduledTaskTriggerKind.INTERVAL -> {
                if (schedule.intervalMinutes !in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES) {
                    AppStrings.get(
                        R.string.scheduled_error_interval_out_of_range,
                        "The fixed interval must be between %1\$d minutes and 7 days",
                        MIN_INTERVAL_MINUTES,
                    )
                } else null
            }
            ScheduledTaskTriggerKind.CRON -> {
                val parsed = parseCron(schedule.cronExpression)
                when {
                    parsed == null -> AppStrings.get(
                        R.string.scheduled_error_cron_invalid,
                        "Invalid Cron expression, must be 5 fields(minute hour day month weekday), e.g. 0 9 * * 1-5",
                    )
                    findNextCronOccurrence(parsed, nowMs, resolveZone(schedule, defaultZone)) == null ->
                        AppStrings.get(
                            R.string.scheduled_error_cron_no_future_run,
                            "This Cron expression has no triggerable time within the next 4 years",
                        )
                    else -> null
                }
            }
        }
    }

    /** Determines whether a due task runs on time, is backfilled within the window, or is skipped. */
    fun evaluateMisfire(
        task: ScheduledTask,
        scheduledForAt: Long,
        nowMs: Long,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): MisfireDecision {
        val zone = resolveZone(task.schedule, defaultZone)
        if (task.schedule.kind != ScheduledTaskTriggerKind.ONCE &&
            isInQuietHours(nowMs, task.quietHours, zone)
        ) {
            return MisfireDecision.Skip(
                AppStrings.get(R.string.scheduled_skip_quiet_hours, "Currently in do-not-disturb hours, this run has been skipped"),
            )
        }
        val delayMs = (nowMs - scheduledForAt).coerceAtLeast(0L)
        // Within 2 minutes is treated as normal system jitter (Android Doze / exact-alarm wake-up latency) and handled as on-time execution.
        if (delayMs <= 2 * 60_000L) return MisfireDecision.RunNow
        return when (task.misfirePolicy) {
            ScheduledTaskMisfirePolicy.SKIP -> MisfireDecision.Skip(
                AppStrings.get(R.string.scheduled_skip_misfire_policy, "Missed the scheduled trigger time, skipped per policy"),
            )
            ScheduledTaskMisfirePolicy.RUN_ONCE_WITHIN_WINDOW -> {
                val windowMs = task.misfireWindowMinutes.coerceIn(5, 24 * 60) * 60_000L
                if (delayMs <= windowMs) {
                    MisfireDecision.RunCatchUp
                } else {
                    MisfireDecision.Skip(
                        AppStrings.get(
                            R.string.scheduled_skip_misfire_window_expired,
                            "Missed trigger time exceeds the catch-up window, skipped",
                        ),
                    )
                }
            }
        }
    }

    fun isInQuietHours(
        epochMs: Long,
        quietHours: ScheduledTaskQuietHours,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!quietHours.enabled) return false
        val start = quietHours.startHour.coerceIn(0, 23) * 60 + quietHours.startMinute.coerceIn(0, 59)
        val end = quietHours.endHour.coerceIn(0, 23) * 60 + quietHours.endMinute.coerceIn(0, 59)
        if (start == end) return false
        val local = Instant.ofEpochMilli(epochMs).atZone(zone)
        val current = local.hour * 60 + local.minute
        return if (start < end) {
            current in start until end
        } else {
            // Cross-midnight window, e.g. 23:00 ~ 07:30
            current >= start || current < end
        }
    }

    /** Parses a standard 5-field Vixie Cron expression: minute hour day-of-month month day-of-week. */
    fun parseCron(expression: String): CronExpression? {
        val fields = expression.trim().split(Regex("\\s+"))
        if (fields.size != 5) return null
        val minutes = parseField(fields[0], 0, 59) ?: return null
        val hours = parseField(fields[1], 0, 23) ?: return null
        val dom = parseField(fields[2], 1, 31) ?: return null
        val months = parseField(fields[3], 1, 12) ?: return null
        val dowRaw = parseField(fields[4], 0, 7) ?: return null
        val dow = dowRaw.mapTo(linkedSetOf()) { if (it == 7) 0 else it }
        return CronExpression(
            minutes = minutes,
            hours = hours,
            daysOfMonth = dom,
            months = months,
            daysOfWeek = dow,
            domWildcard = fields[2] == "*",
            dowWildcard = fields[4] == "*",
        )
    }

    /** Generates a concise schedule summary for cards, confirmation copy, and notifications. */
    fun describeSchedule(
        schedule: ScheduledTaskSchedule,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val timeText = formatHourMinute(schedule.hour, schedule.minute)
        return when (schedule.kind) {
            ScheduledTaskTriggerKind.ONCE -> {
                val target = schedule.triggerAtEpochMs
                if (target == null) {
                    AppStrings.get(R.string.scheduled_schedule_once_unset, "One-time task")
                } else {
                    val zone = resolveZone(schedule, defaultZone)
                    val zdt = Instant.ofEpochMilli(target).atZone(zone)
                    formatMonthDayTime(zdt)
                }
            }
            ScheduledTaskTriggerKind.DAILY ->
                AppStrings.get(R.string.scheduled_schedule_daily, "Every day %1\$s", timeText)
            ScheduledTaskTriggerKind.WEEKDAYS ->
                AppStrings.get(R.string.scheduled_schedule_weekdays, "Weekdays %1\$s", timeText)
            ScheduledTaskTriggerKind.WEEKLY -> {
                val days = schedule.daysOfWeek.filter { it in 1..7 }.sorted()
                when {
                    days.size == 7 -> AppStrings.get(R.string.scheduled_schedule_daily, "Every day %1\$s", timeText)
                    days == listOf(1, 2, 3, 4, 5) -> AppStrings.get(R.string.scheduled_schedule_weekdays, "Weekdays %1\$s", timeText)
                    days == listOf(6, 7) -> AppStrings.get(R.string.scheduled_schedule_weekends, "Weekends %1\$s", timeText)
                    days.isEmpty() -> AppStrings.get(R.string.scheduled_schedule_weekly_generic, "Every week %1\$s", timeText)
                    else -> {
                        val separator = AppStrings.get(R.string.scheduled_day_list_separator, ", ")
                        val dayList = days.joinToString(separator, transform = ::shortDayName)
                        AppStrings.get(R.string.scheduled_schedule_weekly_days, "Every %1\$s %2\$s", dayList, timeText)
                    }
                }
            }
            ScheduledTaskTriggerKind.INTERVAL -> formatInterval(schedule.intervalMinutes)
            ScheduledTaskTriggerKind.CRON -> {
                val expr = schedule.cronExpression.trim().ifBlank {
                    AppStrings.get(R.string.scheduled_cron_unset, "Not configured")
                }
                AppStrings.get(R.string.scheduled_schedule_cron, "Cron · %1\$s", expr)
            }
        }
    }

    /** Formats the next execution time into readable relative + absolute text, e.g. "today 22:00" / "tomorrow 08:00" / "in 30 minutes". */
    fun formatNextRunLabel(
        nextRunAt: Long?,
        nowMs: Long,
        defaultZone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (nextRunAt == null) {
            return AppStrings.get(R.string.scheduled_next_run_unscheduled, "Not scheduled")
        }
        val diffMs = nextRunAt - nowMs
        if (diffMs <= 60_000L) {
            return AppStrings.get(R.string.scheduled_next_run_soon, "Running soon")
        }
        val nowZdt = Instant.ofEpochMilli(nowMs).atZone(defaultZone)
        val targetZdt = Instant.ofEpochMilli(nextRunAt).atZone(defaultZone)
        val hhmm = targetZdt.format(HH_MM_FORMATTER)
        val dayDiff = ChronoUnit.DAYS.between(nowZdt.toLocalDate(), targetZdt.toLocalDate())
        return when (dayDiff) {
            0L -> {
                val minutes = diffMs / 60_000L
                if (minutes < 60L) {
                    AppStrings.quantity(
                        R.plurals.scheduled_next_run_in_minutes,
                        minutes.toInt(),
                        "in %1\$d minutes(%2\$s)",
                        "in %1\$d minutes(%2\$s)",
                        minutes,
                        hhmm,
                    )
                } else {
                    AppStrings.get(R.string.scheduled_next_run_today, "Today %1\$s", hhmm)
                }
            }
            1L -> AppStrings.get(R.string.scheduled_next_run_tomorrow, "Tomorrow %1\$s", hhmm)
            2L -> AppStrings.get(R.string.scheduled_next_run_day_after_tomorrow, "Day after tomorrow %1\$s", hhmm)
            else -> formatMonthDayTime(targetZdt)
        }
    }

    fun formatHourMinute(hour: Int, minute: Int): String =
        String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

    fun formatInterval(minutes: Int): String {
        val clamped = minutes.coerceAtLeast(1)
        return when {
            clamped % (24 * 60) == 0 -> {
                val days = clamped / (24 * 60)
                AppStrings.quantity(
                    R.plurals.scheduled_interval_days,
                    days,
                    "Every %1\$d days",
                    "Every %1\$d days",
                    days,
                )
            }
            clamped % 60 == 0 -> {
                val hours = clamped / 60
                AppStrings.quantity(
                    R.plurals.scheduled_interval_hours,
                    hours,
                    "Every %1\$d hours",
                    "Every %1\$d hours",
                    hours,
                )
            }
            clamped > 60 -> AppStrings.get(
                R.string.scheduled_interval_hours_minutes,
                "Every %1\$d hours %2\$d minutes",
                clamped / 60,
                clamped % 60,
            )
            else -> AppStrings.quantity(
                R.plurals.scheduled_interval_minutes,
                clamped,
                "Every %1\$d minutes",
                "Every %1\$d minutes",
                clamped,
            )
        }
    }

    private fun shortDayName(isoDay: Int): String = when (isoDay) {
        1 -> AppStrings.get(R.string.scheduled_weekday_short_mon, "Mon")
        2 -> AppStrings.get(R.string.scheduled_weekday_short_tue, "Tue")
        3 -> AppStrings.get(R.string.scheduled_weekday_short_wed, "Wed")
        4 -> AppStrings.get(R.string.scheduled_weekday_short_thu, "Thu")
        5 -> AppStrings.get(R.string.scheduled_weekday_short_fri, "Fri")
        6 -> AppStrings.get(R.string.scheduled_weekday_short_sat, "Sat")
        7 -> AppStrings.get(R.string.scheduled_weekday_short_sun, "Sun")
        else -> ""
    }

    private fun formatMonthDayTime(zdt: ZonedDateTime): String =
        AppStrings.get(
            R.string.scheduled_date_time_short,
            "%1\$d/%2\$d %3\$s",
            zdt.monthValue,
            zdt.dayOfMonth,
            zdt.format(HH_MM_FORMATTER),
        )

    private fun findNextCalendarOccurrence(
        afterMs: Long,
        zone: ZoneId,
        quietHours: ScheduledTaskQuietHours,
        projector: (ZonedDateTime) -> ZonedDateTime,
    ): Long? {
        val base = Instant.ofEpochMilli(afterMs).atZone(zone)
        for (offsetDays in 0..366) {
            val candidate = projector(base.plusDays(offsetDays.toLong()))
            val epochMs = candidate.toInstant().toEpochMilli()
            if (epochMs > afterMs && !isInQuietHours(epochMs, quietHours, zone)) {
                return epochMs
            }
        }
        return null
    }

    private fun findNextWeeklyOccurrence(
        afterMs: Long,
        zone: ZoneId,
        hour: Int,
        minute: Int,
        daysOfWeek: Set<Int>,
        quietHours: ScheduledTaskQuietHours,
    ): Long? {
        val base = Instant.ofEpochMilli(afterMs).atZone(zone)
        for (offsetDays in 0..366) {
            val day = base.plusDays(offsetDays.toLong())
            if (day.dayOfWeek.value !in daysOfWeek) continue
            val candidate = day.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            val epochMs = candidate.toInstant().toEpochMilli()
            if (epochMs > afterMs && !isInQuietHours(epochMs, quietHours, zone)) {
                return epochMs
            }
        }
        return null
    }

    private fun findNextCronOccurrence(
        cron: CronExpression,
        afterMs: Long,
        zone: ZoneId,
        quietHours: ScheduledTaskQuietHours = ScheduledTaskQuietHours(),
    ): Long? {
        var cursor = Instant.ofEpochMilli(afterMs)
            .atZone(zone)
            .truncatedTo(ChronoUnit.MINUTES)
            .plusMinutes(1)
        val limit = cursor.plusDays(CRON_MAX_SEARCH_DAYS.toLong())
        while (cursor.isBefore(limit)) {
            if (cursor.monthValue !in cron.months) {
                cursor = cursor.withDayOfMonth(1).withHour(0).withMinute(0).plusMonths(1)
                continue
            }
            val domMatch = cursor.dayOfMonth in cron.daysOfMonth
            val cronDow = if (cursor.dayOfWeek == DayOfWeek.SUNDAY) 0 else cursor.dayOfWeek.value
            val dowMatch = cronDow in cron.daysOfWeek
            val dayMatches = when {
                cron.domWildcard && cron.dowWildcard -> true
                cron.domWildcard -> dowMatch
                cron.dowWildcard -> domMatch
                else -> domMatch || dowMatch
            }
            if (!dayMatches) {
                cursor = cursor.withHour(0).withMinute(0).plusDays(1)
                continue
            }
            if (cursor.hour !in cron.hours) {
                cursor = cursor.withMinute(0).plusHours(1)
                continue
            }
            if (cursor.minute !in cron.minutes) {
                cursor = cursor.plusMinutes(1)
                continue
            }
            val epochMs = cursor.toInstant().toEpochMilli()
            if (epochMs > afterMs && !isInQuietHours(epochMs, quietHours, zone)) {
                return epochMs
            }
            cursor = cursor.plusMinutes(1)
        }
        return null
    }

    private fun parseField(raw: String, min: Int, max: Int): Set<Int>? {
        if (raw.isBlank()) return null
        val result = linkedSetOf<Int>()
        for (part in raw.split(',')) {
            if (part.isBlank()) return null
            val stepPieces = part.split('/')
            if (stepPieces.size > 2) return null
            val step = if (stepPieces.size == 2) {
                stepPieces[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            } else 1
            val rangePart = stepPieces[0]
            val (start, end) = when {
                rangePart == "*" -> min to max
                '-' in rangePart -> {
                    val bounds = rangePart.split('-')
                    if (bounds.size != 2) return null
                    val s = bounds[0].toIntOrNull() ?: return null
                    val e = bounds[1].toIntOrNull() ?: return null
                    if (s !in min..max || e !in min..max || s > e) return null
                    s to e
                }
                else -> {
                    val single = rangePart.toIntOrNull() ?: return null
                    if (single !in min..max) return null
                    if (stepPieces.size == 2) single to max else single to single
                }
            }
            var value = start
            while (value <= end) {
                result.add(value)
                value += step
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private val HH_MM_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** Parses ISO-8601 or local `yyyy-MM-dd HH:mm` into epochMs for model tools to use. */
    fun parseIsoOrLocalDateTime(raw: String, defaultZone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { ZonedDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        val normalized = text.replace(' ', 'T')
        runCatching {
            LocalDateTime.parse(normalized).atZone(defaultZone).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return null
    }
}
