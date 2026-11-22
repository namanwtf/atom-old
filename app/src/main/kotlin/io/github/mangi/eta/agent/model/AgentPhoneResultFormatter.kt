package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

internal object AgentPhoneResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") == "unconfirmed") return null
        when (toolName) {
            "list_calendars" -> return calendarCount(json)
            "list_alarms" -> return alarmCount(json)
            "list_active_timers" -> return timerCount(json)
            "read_note" -> return json.optJSONObject("note")?.let {
                AppStrings.get(R.string.trace_phone_note_read, "Read note")
            }
            "read_calendar_event" -> return json.optJSONObject("event")?.let {
                AppStrings.get(R.string.trace_phone_event_read, "Read event")
            }
        }
        if (json.optString("status") == "dispatched") {
            return when (toolName) {
                "set_alarm" -> withDetail(
                    AppStrings.get(R.string.trace_phone_alarm_submitted, "Alarm request submitted"),
                    alarmTime(json),
                )
                "set_timer" -> withDetail(
                    AppStrings.get(R.string.trace_phone_timer_submitted, "Timer request submitted"),
                    timerDuration(json),
                )
                else -> null
            }
        }
        if (json.opt("verified") != true) return null
        return when (toolName) {
            "set_alarm" -> withDetail(
                AppStrings.get(R.string.trace_phone_alarm_created, "Alarm created"),
                alarmTime(json.optJSONObject("alarm")),
            )
            "set_timer" -> withDetail(
                AppStrings.get(R.string.trace_phone_timer_set, "Timer set"),
                timerDuration(json.optJSONObject("timer") ?: json),
            )
            "update_alarm_time" -> alarmTimeChange(json)
            "set_alarm_enabled" -> alarmEnabled(json)
            "delete_alarm" -> withDetail(
                AppStrings.get(R.string.trace_phone_alarm_deleted, "Alarm deleted"),
                alarmTime(json.optJSONObject("before")),
            )
            "create_calendar_event", "create_calendar_events" -> {
                val count = json.integer("count", 1, 30)
                if (count != null) {
                    AppStrings.quantity(
                        R.plurals.trace_phone_events_created,
                        count,
                        "Created %1\$d events",
                        "Created %1\$d events",
                        count,
                    )
                } else {
                    AppStrings.get(R.string.trace_phone_event_created, "Event created")
                }
            }
            "update_calendar_event" -> calendarChange(json)
            "delete_calendar_event" -> AppStrings.get(R.string.trace_phone_event_deleted, "Event deleted")
            "create_note" -> AppStrings.get(R.string.trace_phone_note_created, "Note created")
            "delete_note" -> if (json.optString("mode") == "recycle") {
                AppStrings.get(R.string.trace_phone_note_recycled, "Note moved to recycle bin")
            } else {
                null
            }
            else -> null
        }
    }

    private fun calendarCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return AppStrings.get(R.string.trace_phone_calendars_empty, "No usable calendars found")
        val base = AppStrings.quantity(
            R.plurals.trace_phone_calendars_found,
            count,
            "Found %1\$d usable calendars",
            "Found %1\$d usable calendars",
            count,
        )
        return if (json.opt("has_more") == true) {
            "$base · ${AppStrings.get(R.string.trace_res_has_more, "More")}"
        } else {
            base
        }
    }

    private fun alarmCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return AppStrings.get(R.string.trace_phone_alarms_empty, "No alarms")
        val base = AppStrings.quantity(
            R.plurals.trace_phone_alarms_found,
            count,
            "Found %1\$d alarms",
            "Found %1\$d alarms",
            count,
        )
        return if (json.opt("has_more") == true) {
            "$base · ${AppStrings.get(R.string.trace_res_has_more, "More")}"
        } else {
            base
        }
    }

    private fun timerCount(json: JSONObject): String? {
        val items = json.optJSONArray("items") ?: return null
        val count = items.length()
        if (count == 0) return AppStrings.get(R.string.trace_phone_timers_empty, "No active timers")
        val base = AppStrings.quantity(
            R.plurals.trace_phone_timers_found,
            count,
            "Found %1\$d timers",
            "Found %1\$d timers",
            count,
        )
        return if (json.opt("has_more") == true) {
            "$base · ${AppStrings.get(R.string.trace_res_has_more, "More")}"
        } else {
            base
        }
    }

    private fun alarmTime(json: JSONObject?): String? {
        val hour = json?.integer("hour", 0, 23) ?: return null
        val minute = json.integer("minute", 0, 59) ?: return null
        return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
    }

    private fun timerDuration(json: JSONObject): String? {
        val seconds = json.integer("duration_seconds", 1, 86400) ?: return null
        return buildList {
            if (seconds >= 3600) {
                val hours = seconds / 3600
                add(
                    AppStrings.quantity(
                        R.plurals.trace_phone_dur_hours,
                        hours,
                        "%1\$d hours",
                        "%1\$d hours",
                        hours,
                    )
                )
            }
            if (seconds % 3600 >= 60) {
                val minutes = seconds % 3600 / 60
                add(
                    AppStrings.quantity(
                        R.plurals.trace_phone_dur_minutes,
                        minutes,
                        "%1\$d minutes",
                        "%1\$d minutes",
                        minutes,
                    )
                )
            }
            if (seconds % 60 > 0) {
                val remSeconds = seconds % 60
                add(
                    AppStrings.quantity(
                        R.plurals.trace_phone_dur_seconds,
                        remSeconds,
                        "%1\$d seconds",
                        "%1\$d seconds",
                        remSeconds,
                    )
                )
            }
        }.joinToString(" ")
    }

    private fun alarmTimeChange(json: JSONObject): String {
        val before = alarmTime(json.optJSONObject("before"))
        val alarm = json.optJSONObject("alarm")
        val after = alarmTime(alarm)
        val summary = when {
            before != null && after != null && before == after -> AppStrings.get(
                R.string.trace_phone_alarm_unchanged,
                "Alarm time unchanged · %1\$s",
                after,
            )
            before != null && after != null -> AppStrings.get(
                R.string.trace_phone_alarm_changed,
                "Alarm time: %1\$s → %2\$s",
                before,
                after,
            )
            else -> withDetail(
                AppStrings.get(R.string.trace_phone_alarm_updated, "Alarm time updated"),
                after,
            )
        }
        return if (alarm?.opt("enabled") == false) {
            "$summary · ${AppStrings.get(R.string.trace_phone_alarm_disabled_suffix, "not enabled")}"
        } else {
            summary
        }
    }

    private fun alarmEnabled(json: JSONObject): String? {
        val alarm = json.optJSONObject("alarm") ?: return null
        val enabled = alarm.opt("enabled") as? Boolean ?: return null
        val before = json.optJSONObject("before")?.opt("enabled") as? Boolean
        val summary = when {
            before == enabled && enabled ->
                AppStrings.get(R.string.trace_phone_alarm_kept_on, "Alarm kept on")
            before == enabled ->
                AppStrings.get(R.string.trace_phone_alarm_kept_off, "Alarm kept off")
            enabled ->
                AppStrings.get(R.string.trace_phone_alarm_turned_on, "Alarm turned on")
            else ->
                AppStrings.get(R.string.trace_phone_alarm_turned_off, "Alarm turned off")
        }
        return withDetail(summary, alarmTime(alarm))
    }

    private fun calendarChange(json: JSONObject): String {
        val updated = AppStrings.get(R.string.trace_phone_event_updated, "Event updated")
        val before = json.optJSONObject("before") ?: return updated
        val after = json.optJSONObject("event") ?: return updated
        val fields = listOf(
            AppStrings.get(R.string.trace_phone_field_title, "Title") to listOf("title"),
            AppStrings.get(R.string.trace_phone_field_time, "Time") to listOf("dtstart", "dtend", "eventTimezone", "allDay"),
            AppStrings.get(R.string.trace_phone_field_location, "Location") to listOf("eventLocation"),
            AppStrings.get(R.string.trace_phone_field_description, "Description") to listOf("description"),
            AppStrings.get(R.string.trace_phone_field_reminders, "Reminders") to listOf("reminders"),
        )
        val changes = fields.mapNotNull { (label, keys) ->
            label.takeIf {
                keys.any { key ->
                    before.has(key) && after.has(key) &&
                        calendarValue(before, key) != calendarValue(after, key)
                }
            }
        }
        if (changes.isNotEmpty()) {
            val separator = AppStrings.get(R.string.trace_list_separator, ", ")
            return "$updated · ${changes.joinToString(separator)}"
        }
        val keys = fields.flatMap { it.second }
        val complete = keys.all { hasComparableCalendarValue(before, it) && hasComparableCalendarValue(after, it) }
        return if (complete) {
            AppStrings.get(R.string.trace_phone_event_unchanged, "Event unchanged")
        } else {
            updated
        }
    }

    private fun hasComparableCalendarValue(json: JSONObject, key: String): Boolean {
        if (!json.has(key)) return false
        val value = json.opt(key)
        if (key != "reminders") return value == JSONObject.NULL || value is String || value is Number
        if (value !is JSONArray) return false
        return (0 until value.length()).all { index ->
            val reminder = value.optJSONObject(index)
            reminder != null && listOf("minutes", "method").all {
                reminder.opt(it) is String || reminder.opt(it) is Number
            }
        }
    }

    private fun calendarValue(json: JSONObject, key: String): Any? {
        val value = json.opt(key)
        if (key != "reminders" || value !is JSONArray) return value
        // Reminder order does not affect behavior; compare only the minute and reminder method, ignoring the record identifiers returned by the Provider.
        return (0 until value.length()).map { index ->
            value.optJSONObject(index)?.let { reminder ->
                "${reminder.opt("minutes")}:${reminder.opt("method")}"
            }
        }.sortedBy { it }
    }

    private fun JSONObject.integer(key: String, minimum: Int, maximum: Int): Int? =
        (opt(key) as? Number)?.toString()?.toLongOrNull()
            ?.takeIf { it in minimum.toLong()..maximum.toLong() }?.toInt()

    private fun withDetail(summary: String, detail: String?): String =
        if (detail == null) summary else "$summary · $detail"
}
