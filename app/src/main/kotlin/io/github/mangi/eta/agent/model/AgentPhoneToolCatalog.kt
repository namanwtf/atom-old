package io.github.mangi.eta.agent.model

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.agent.tool.SystemStateControls
import io.github.mangi.eta.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

/** 同一份领域目录用于模型、权限元数据和工具页，避免新增入口时漏接一端。 */
internal object AgentPhoneToolCatalog {
    data class Entry(
        val name: String,
        @StringRes private val titleRes: Int,
        private val titleFallback: String,
        val description: String,
        @StringRes private val descriptionRes: Int,
        val write: Boolean,
        val root: RootRequirement,
        val colorOs: Boolean = false,
        val personal: Boolean = false,
        val direct: Boolean = false,
    ) {
        val title: String
            get() = AppStrings.get(titleRes, titleFallback)

        fun localizedDescription(): String = AppStrings.get(descriptionRes, description)
    }

    val entries =
        listOf(
            Entry(
                name = "get_flashlight",
                titleRes = R.string.tool_cat_get_flashlight_title,
                titleFallback = "Flashlight status",
                description = "Query the flashlight's actual state and whether it is occupied by the camera.",
                descriptionRes = R.string.tool_cat_get_flashlight_desc,
                write = false,
                root = RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                name = "set_flashlight",
                titleRes = R.string.tool_cat_set_flashlight_title,
                titleFallback = "Set flashlight",
                description = "Turn the flashlight on or off and verify via system callback; do not toggle again if already in the target state.",
                descriptionRes = R.string.tool_cat_set_flashlight_desc,
                write = true,
                root = RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                name = "get_device_state",
                titleRes = R.string.tool_cat_get_device_state_title,
                titleFallback = "Query system switch",
                description = "Query a specified system switch without using the GUI.",
                descriptionRes = R.string.tool_cat_get_device_state_desc,
                write = false,
                root = RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                name = "get_display_state",
                titleRes = R.string.tool_cat_get_display_state_title,
                titleFallback = "Display state",
                description = "Query brightness, auto-brightness, rotation, and screen-off timeout.",
                descriptionRes = R.string.tool_cat_get_display_state_desc,
                write = false,
                root = RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                name = "set_brightness",
                titleRes = R.string.tool_cat_set_brightness_title,
                titleFallback = "Set brightness",
                description = "Set brightness as a percentage of the system brightness slider and read back to verify; fail explicitly when no reliable percentage interface is available.",
                descriptionRes = R.string.tool_cat_set_brightness_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                name = "set_screen_timeout",
                titleRes = R.string.tool_cat_set_screen_timeout_title,
                titleFallback = "Set screen-off timeout",
                description = "Set the number of seconds before the screen turns off after inactivity, and read back to verify.",
                descriptionRes = R.string.tool_cat_set_screen_timeout_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                name = "get_sound_state",
                titleRes = R.string.tool_cat_get_sound_state_title,
                titleFallback = "Sound state",
                description = "Read various volume levels, ringer, and do-not-disturb state.",
                descriptionRes = R.string.tool_cat_get_sound_state_desc,
                write = false,
                root = RootRequirement.NONE,
                direct = true,
            ),
            Entry(
                name = "set_sound_mode",
                titleRes = R.string.tool_cat_set_sound_mode_title,
                titleFallback = "Set ringer mode",
                description = "Set normal, silent, or vibrate mode and verify; the non-privileged identity may require do-not-disturb access permission.",
                descriptionRes = R.string.tool_cat_set_sound_mode_desc,
                write = true,
                root = RootRequirement.PARTIAL,
            ),
            Entry(
                name = "set_do_not_disturb",
                titleRes = R.string.tool_cat_set_do_not_disturb_title,
                titleFallback = "Set do-not-disturb mode",
                description = "Directly set the do-not-disturb policy and read back to verify.",
                descriptionRes = R.string.tool_cat_set_do_not_disturb_desc,
                write = true,
                root = RootRequirement.REQUIRED,
            ),
            Entry(
                name = "get_hotspot",
                titleRes = R.string.tool_cat_get_hotspot_title,
                titleFallback = "Hotspot status",
                description = "Query whether the internet-sharing hotspot is on; requires a compatible system interface.",
                descriptionRes = R.string.tool_cat_get_hotspot_desc,
                write = false,
                root = RootRequirement.REQUIRED,
                direct = true,
            ),
            Entry(
                name = "set_hotspot",
                titleRes = R.string.tool_cat_set_hotspot_title,
                titleFallback = "Set personal hotspot",
                description = "Turn network sharing on or off using the existing hotspot configuration; does not change the name or password.",
                descriptionRes = R.string.tool_cat_set_hotspot_desc,
                write = true,
                root = RootRequirement.REQUIRED,
            ),
            Entry(
                name = "list_calendars",
                titleRes = R.string.tool_cat_list_calendars_title,
                titleFallback = "Available calendars",
                description = "List the writable calendar IDs and names for the current user; when creating, you must pick a real calendar and must not guess calendar_id.",
                descriptionRes = R.string.tool_cat_list_calendars_desc,
                write = false,
                root = RootRequirement.PARTIAL,
                personal = true,
            ),
            Entry(
                name = "read_calendar_event",
                titleRes = R.string.tool_cat_read_calendar_event_title,
                titleFallback = "Event details",
                description = "Read the live event and reminders by raw event_id; do not use the DMP document ID.",
                descriptionRes = R.string.tool_cat_read_calendar_event_desc,
                write = false,
                root = RootRequirement.PARTIAL,
                personal = true,
            ),
            Entry(
                name = "create_calendar_event",
                titleRes = R.string.tool_cat_create_calendar_event_title,
                titleFallback = "Create event",
                description = "Directly create an event and reminders, and read back to confirm after the transaction completes. One-off events use start and end times; all-day events use UTC midnight and an exclusive end midnight. After a timeout, query first; do not create duplicates.",
                descriptionRes = R.string.tool_cat_create_calendar_event_desc,
                write = true,
                root = RootRequirement.PARTIAL,
            ),
            Entry(
                name = "create_calendar_events",
                titleRes = R.string.tool_cat_create_calendar_events_title,
                titleFallback = "Create events in bulk",
                description = "Create multiple events and reminders in the same calendar in one call; validate all parameters first, then write in a single transaction and verify each. At most 30 items; if results are unconfirmed, query first; do not create duplicates.",
                descriptionRes = R.string.tool_cat_create_calendar_events_desc,
                write = true,
                root = RootRequirement.PARTIAL,
            ),
            Entry(
                name = "update_calendar_event",
                titleRes = R.string.tool_cat_update_calendar_event_title,
                titleFallback = "Update event",
                description = "Modify event fields and reminders by raw event_id, and read back to confirm; when changing the time you must provide both start and end times. Recurring events are not modified for now.",
                descriptionRes = R.string.tool_cat_update_calendar_event_desc,
                write = true,
                root = RootRequirement.PARTIAL,
            ),
            Entry(
                name = "delete_calendar_event",
                titleRes = R.string.tool_cat_delete_calendar_event_title,
                titleFallback = "Delete event",
                description = "Delete the exact raw event_id; recurring events must explicitly specify scope=series, and must not treat an index ID as an event ID.",
                descriptionRes = R.string.tool_cat_delete_calendar_event_desc,
                write = true,
                root = RootRequirement.PARTIAL,
            ),
            Entry(
                name = "update_alarm_time",
                titleRes = R.string.tool_cat_update_alarm_time_title,
                titleFallback = "Change alarm time",
                description = "Change the hour and minute by alarm_id, keep the original enabled state, and verify.",
                descriptionRes = R.string.tool_cat_update_alarm_time_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                colorOs = true,
            ),
            Entry(
                name = "set_alarm_enabled",
                titleRes = R.string.tool_cat_set_alarm_enabled_title,
                titleFallback = "Alarm switch",
                description = "Enable or disable the exact alarm_id and read back to verify; do not match multiple alarms by fuzzy time.",
                descriptionRes = R.string.tool_cat_set_alarm_enabled_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                colorOs = true,
            ),
            Entry(
                name = "delete_alarm",
                titleRes = R.string.tool_cat_delete_alarm_title,
                titleFallback = "Delete alarm",
                description = "Delete the exact alarm_id and then verify; do not use the DMP index ID.",
                descriptionRes = R.string.tool_cat_delete_alarm_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                colorOs = true,
            ),
            Entry(
                name = "create_note",
                titleRes = R.string.tool_cat_create_note_title,
                titleFallback = "Create note",
                description = "Directly create a system note, parse the actual result UUID, and read back; do not create again if the result is unconfirmed.",
                descriptionRes = R.string.tool_cat_create_note_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                colorOs = true,
            ),
            Entry(
                name = "read_note",
                titleRes = R.string.tool_cat_read_note_title,
                titleFallback = "Note details",
                description = "Read the note body by raw note_id UUID; do not use the DMP document ID.",
                descriptionRes = R.string.tool_cat_read_note_desc,
                write = false,
                root = RootRequirement.REQUIRED,
                colorOs = true,
                personal = true,
            ),
            Entry(
                name = "delete_note",
                titleRes = R.string.tool_cat_delete_note_title,
                titleFallback = "Move note to trash",
                description = "Move the note to the trash by raw note_id UUID and verify; this is not a permanent deletion.",
                descriptionRes = R.string.tool_cat_delete_note_desc,
                write = true,
                root = RootRequirement.REQUIRED,
                colorOs = true,
            ),
        )
    val names = entries.mapTo(linkedSetOf()) { it.name }
    val direct = entries.filter { it.direct }.mapTo(linkedSetOf()) { it.name }
    val reads = entries.filter { !it.write && !it.direct }.mapTo(linkedSetOf()) { it.name }
    val writes = entries.filter { it.write && !it.direct }.mapTo(linkedSetOf()) { it.name }

    fun appendTo(tools: JSONArray, direct: Boolean, reads: Boolean, writes: Boolean) {
        entries
            .filter { if (it.direct) direct else if (it.write) writes else reads }
            .forEach { tools.put(schema(it)) }
    }

    private fun schema(entry: Entry): JSONObject {
        val p = JSONObject()
        val required = mutableListOf<String>()
        fun field(key: String, schema: JSONObject, need: Boolean = true) {
            p.put(key, schema)
            if (need) required += key
        }
        fun id(key: String) = field(key, text("Raw object ID", 36))
        when (entry.name) {
            "set_flashlight",
            "set_hotspot" -> field("enabled", JSONObject().put("type", "boolean"))
            "get_device_state" -> field("target", enum(SystemStateControls.targets))
            "set_brightness" -> field("percent", integer(0, 100))
            "set_screen_timeout" -> field("seconds", integer(15, 1800))
            "set_sound_mode" -> field("mode", enum(listOf("normal", "silent", "vibrate")))
            "set_do_not_disturb" ->
                field("mode", enum(listOf("off", "priority", "alarms", "silent")))
            "read_calendar_event" -> id("event_id")
            "create_calendar_events" -> {
                field("calendar_id", text("Writable calendar ID", 19), false)
                val single =
                    schema(entries.first { it.name == "create_calendar_event" })
                        .getJSONObject("function")
                        .getJSONObject("parameters")
                single.getJSONObject("properties").remove("calendar_id")
                field(
                    "events",
                    JSONObject()
                        .put("type", "array")
                        .put("minItems", 1)
                        .put("maxItems", 30)
                        .put("items", single),
                )
            }
            "create_calendar_event",
            "update_calendar_event" -> {
                val create = entry.name == "create_calendar_event"
                if (create)
                    field("calendar_id", text("Writable calendar ID returned by list_calendars; can be omitted when there is only one calendar", 19), false)
                else id("event_id")
                field("title", text("Event title", 200), create)
                field("start_time", text("ISO 8601 start time with offset", 64), create)
                field("end_time", text("ISO 8601 end time with offset", 64), create)
                field("timezone", text("IANA timezone, e.g. Asia/Shanghai", 80), false)
                field("all_day", JSONObject().put("type", "boolean"), false)
                field("location", text("Location", 500), false)
                field("description", text("Description", 8000), false)
                field(
                    "reminder_minutes",
                    JSONObject()
                        .put("type", "array")
                        .put("items", integer(0, 40320))
                        .put("maxItems", 5),
                    false,
                )
            }
            "delete_calendar_event" -> {
                id("event_id")
                field("scope", enum(listOf("event", "series")), false)
            }
            "update_alarm_time" -> {
                id("alarm_id")
                field("hour", integer(0, 23))
                field("minute", integer(0, 59))
            }
            "set_alarm_enabled" -> {
                id("alarm_id")
                field("enabled", JSONObject().put("type", "boolean"))
            }
            "delete_alarm" -> id("alarm_id")
            "create_note" -> {
                field("title", text("Title", 200), false)
                field("content", text("Note body", 20000))
            }
            "read_note",
            "delete_note" -> id("note_id")
        }
        return AgentPersonalSearchToolCatalog.function(
            entry.name,
            entry.description,
            p,
            *required.toTypedArray(),
        )
    }

    private fun text(description: String, max: Int) =
        JSONObject().put("type", "string").put("description", description).put("maxLength", max)

    private fun integer(min: Int, max: Int) =
        JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)

    private fun enum(values: List<String>) =
        JSONObject().put("type", "string").put("enum", JSONArray(values))
}
