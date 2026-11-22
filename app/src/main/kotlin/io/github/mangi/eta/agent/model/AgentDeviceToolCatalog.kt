package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Structured schemas for common device capabilities; whether they are exposed to the model is decided by risk group. */
internal object AgentDeviceToolCatalog {
    fun appendTo(
        tools: JSONArray,
        directTools: Boolean,
        sensitiveReadTools: Boolean,
        sensitiveActionTools: Boolean,
    ) {
        AgentPhoneToolCatalog.appendTo(tools, directTools, sensitiveReadTools, sensitiveActionTools)
        if (directTools) appendDirectTools(tools)
        if (sensitiveReadTools) appendSensitiveReadTools(tools)
        if (sensitiveActionTools) appendSensitiveActionTools(tools)
    }

    private fun appendDirectTools(tools: JSONArray) {
        tools
            .put(function(
                "inspect_app",
                "Read the version, UID, install source and path, enabled state, and requested permissions of a specified app in the current Android user; the app need not have a launcher entry. Only supports the user Eta belongs to; does not query across work profiles. The permission list is paginated by permission_offset/permission_limit.",
                properties(
                    "package_name" to string("Exact Android package name", 255),
                    "user_id" to userId(),
                    "permission_offset" to integer("Starting index for the requested permission list, default 0", 0, Int.MAX_VALUE),
                    "permission_limit" to integer("Maximum number of permissions to return, default 100", 1, 200),
                ),
                "package_name",
            ))
            .put(
                function(
                    "set_alarm",
                    "Directly create a system alarm without using the GUI. For relative dates, first resolve via get_current_context; hour/minute use device local time. If the system rejects direct action, it may only open the clock page.",
                    properties(
                        "hour" to integer("0 to 23", 0, 23),
                        "minute" to integer("0 to 59", 0, 59),
                        "label" to string("Alarm label, at most 100 characters", 100),
                        "repeat_days" to stringArray(
                            "Days of week to repeat; omit for one-time only",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                        "vibrate" to boolean("Whether to vibrate, default true"),
                    ),
                    "hour", "minute",
                ),
            )
            .put(
                function(
                    "set_timer",
                    "Directly create a system timer without using the GUI. duration_seconds must be between 1 and 86400 seconds.",
                    properties(
                        "duration_seconds" to integer("Timer duration in seconds", 1, 86_400),
                        "label" to string("Timer label, at most 100 characters", 100),
                    ),
                    "duration_seconds",
                ),
            )
            .put(emptyFunction("device_status", "Read battery, memory, storage, system version, and uptime."))
            .put(emptyFunction("network_info", "Read the current network type, connectivity validation state, and basic current Wi-Fi info; does not return saved passwords."))
            .put(limitFunction("top_memory_apps", "List processes with the highest memory usage by current RSS."))
            .put(limitFunction("top_storage_apps", "List apps with the highest storage usage by app, data, and cache combined."))
            .put(
                function(
                    "media_control",
                    "Directly control the current media session; do not operate the player GUI.",
                    properties(
                        "action" to enumString(
                            "Media action",
                            "play", "pause", "play_pause", "next", "previous", "stop",
                        ),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "set_volume",
                    "Directly set system volume; do not operate the volume GUI.",
                    properties(
                        "stream" to enumString("Volume stream", "media", "alarm", "ring", "notification", "voice_call", "system"),
                        "percent" to integer("Volume percentage from 0 to 100", 0, 100),
                        "user_id" to userId(),
                    ),
                    "stream", "percent",
                ),
            )
    }

    private fun appendSensitiveReadTools(tools: JSONArray) {
        AgentPersonalSearchToolCatalog.appendTo(tools)
        tools
            .put(
                function(
                    "get_setting",
                    "Read a single Android Settings value. The result may contain sensitive information such as device identifiers; the raw result is not persisted.",
                    properties(
                        "namespace" to enumString("Settings namespace", "system", "secure", "global"),
                        "key" to string("Exact settings key", 200),
                        "user_id" to userId(),
                    ),
                    "namespace", "key",
                ),
            )
            .put(
                function(
                    "wifi_credentials",
                    "Read the Wi-Fi names and passwords saved on the phone. The raw result is not persisted.",
                    properties(
                        "ssid" to string("Optional exact Wi-Fi name (SSID)", 128),
                        "limit" to integer("Maximum number to return, default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_notifications",
                    "Read the title and body of notifications currently in the notification shade. Results are not written to the persistent session.",
                    properties(
                        "package_name" to string("Optional exact app package name filter", 255),
                        "limit" to integer("Maximum number to return, default 10", 1, 20),
                    ),
                ),
            )
            .put(
                function(
                    "search_notification_history",
                    "Search notifications recorded by Eta in the last 7 days after the user granted notification access. Raw results are not written to the persistent session.",
                    properties(
                        "query" to string("Optional title or body keyword", 200),
                        "package_name" to string("Optional exact package name", 255),
                        "max_age_hours" to integer("Hours to look back, default 24", 1, 168),
                        "limit" to integer("Maximum number to return, default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_app_activity",
                    "Read the chronological order of recently opened apps; requires system usage access.",
                    properties(
                        "package_name" to string("Optional exact package name", 255),
                        "max_age_hours" to integer("Hours to look back, default 24", 1, 168),
                        "limit" to integer("Maximum number to return, default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "app_usage_summary",
                    "Summarize recent app usage by foreground duration; requires system usage access.",
                    properties(
                        "max_age_hours" to integer("Hours to aggregate, default 24", 1, 168),
                        "limit" to integer("Maximum number to return, default 20", 1, 50),
                    ),
                ),
            )
            .put(emptyFunction("get_current_location", "Read the most recent location already known to the system; does not continuously monitor or actively wake GPS."))
            .put(emptyFunction("get_device_environment", "Read lock screen, do-not-disturb, ringtone, audio output, and external display state."))
            .put(
                function(
                    "list_alarms",
                    "Read the raw alarm IDs, times, repeat rules, and on/off state from the clock; filterable and paginated.",
                    properties(
                        "enabled_only" to boolean("Whether to return only enabled alarms, default true"),
                        "query" to string("Optional label keyword", 200),
                        "hour" to integer("Specified hour", 0, 23),
                        "minute" to integer("Specified minute", 0, 59),
                        "offset" to integer("Pagination start index", 0, 10000),
                        "limit" to integer("Maximum number to return, default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "list_active_timers",
                    "Read running or paused timers in the ColorOS clock.",
                    properties("limit" to integer("Maximum number to return, default 20", 1, 50)),
                ),
            )
            .put(searchFunction("search_clipboard_history", "Search the clipboard history saved by the current system input method."))
            .put(
                function(
                    "get_health_summary",
                    "Summarize steps, sleep, exercise, heart rate, weight, and blood oxygen from system health data; does not return raw measurement series.",
                    properties("days" to integer("Number of recent days to aggregate, default 7", 1, 30)),
                ),
            )
            .put(
                function(
                    "read_sms_code",
                    "Extract only 4-to-8-digit verification codes, sender, and time from recent SMS; does not return full SMS bodies.",
                    properties(
                        "max_age_minutes" to integer("Only check SMS within this many minutes, default 10", 1, 1_440),
                    ),
                ),
            )
            .put(
                function(
                    "get_logcat",
                    "Read a bounded sample from the device log ring buffer; still requires root. First collect the most recent scan_lines lines by PID/tag/level/buffer, then filter by since/query and return the most recent max_lines lines. No match does not mean older records do not exist; check scan_start/end, scan_limited, has_more, and complete_within_scan. Logs are not isolated per Android user.",
                    properties(
                        "query" to string("Optional filter text", 200),
                        "max_lines" to integer("Maximum number of matched lines to return, default 200; a separate total character cap also applies", 1, 500),
                        "scan_lines" to integer("Maximum number of recent log lines to collect, default 2000; different from the number returned", 1, 10_000),
                        "pid" to integer("Collect only this process PID", 1, Int.MAX_VALUE),
                        "tag" to string("Exact log tag; letters, digits, underscores, dots, or hyphens only", 100)
                            .put("pattern", "^[A-Za-z0-9_.-]{1,100}$"),
                        "level" to enumString("Minimum priority level, default V", "V", "D", "I", "W", "E", "F"),
                        "buffer" to enumString("Log buffer, default default", "main", "system", "crash", "events", "radio", "all", "default"),
                        "since" to string("Start time within the sample, ISO 8601 with timezone, e.g. 2026-10-05T08:00:00Z", 64),
                    ),
                ),
            )
            .put(searchFunction("search_media", "Search images in the current media library; filter by file name or album path, and return metadata and content URI ordered newest to oldest by ingest time. Use this tool to query the latest images; set match=content to find historical images by text, description, or tags. This tool does not read image content."))
            .put(searchFunction("search_audio", "Search music and audio files on this device; filter by title or file name."))
            .put(searchFunction("search_recordings", "Search recording files on this device. Results come from the system media library; does not read transcriptions or audio content."))
            .put(searchFunction("search_files", "Search documents and downloaded files in shared storage; filter by file name. Does not traverse other apps' private directories."))
            .put(searchFunction("search_calendar_events", "Search the live calendar. A time window must provide both start_time/end_time, at most 366 days, and returns instances within the window; without a window, returns raw event definitions."))
            .put(searchFunction("search_contacts", "Search contacts in the system address book, returning names and lookup URIs."))
            .put(searchFunction("search_call_history", "Search call history; filter by number or cached contact name."))
            .put(searchFunction("search_messages", "Search SMS; filter by sender or body keyword. Results are sensitive personal content."))
            .put(searchFunction("search_downloads", "Search system download records; filter by file name or description."))
            .put(searchFunction("search_notes", "Query notes by title, body, or time, preferring the original source. If the raw interface is temporarily unreadable, return a clearly marked historical index; set current_only=true when the latest state is required."))
            .put(searchFunction("search_coloros_recordings", "Search ordinary and call recordings in the ColorOS recorder app, returning name, duration, type, and file path."))
            .put(searchFunction("search_recording_summaries", "Search transcription summaries and note content associated with ColorOS recordings. Only available when the recorder app has generated summaries."))
            .put(searchFunction("search_system_memories", "Query the historical index, summaries, and original text of system memories. Use the corresponding domain query for structured information such as bills and itineraries; does not read the Agent's own MEMORY.md."))
            .put(searchFunction("search_saved_places", "Search places saved or recognized in system memory."))
            .put(searchFunction("search_personal_orders", "Search food delivery, shopping, express delivery, ticket, and travel orders recognized in system memory."))
            .put(searchFunction("search_qq_chat_images", "Search the QQ chat image cache, returning time, size, type, and private path of recent files. Only available when QQ is installed and the cache still exists."))
            .put(searchFunction("search_wechat_chat_images", "Search the WeChat chat image cache, returning time, size, and private path of recent files. Only available when WeChat is installed and the cache still exists."))
    }

    private fun appendSensitiveActionTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "set_setting",
                    "Modify a single Android Settings value and read back to verify. user_id defaults to the user Eta belongs to; the global namespace affects the device. Only verified=true means the target value is confirmed; do not blindly replay when unconfirmed.",
                    properties(
                        "namespace" to enumString("Settings namespace", "system", "secure", "global"),
                        "key" to string("Exact settings key", 200),
                        "value" to string("New value", 2_000),
                        "user_id" to userId(),
                    ),
                    "namespace", "key", "value",
                ),
            )
            .put(
                function(
                    "set_device_state",
                    "Directly set Wi-Fi, Bluetooth, mobile data, airplane mode, location, NFC, battery saver, auto-rotate, auto-brightness, and dark mode, and read back the state. If an async toggle has not finished, it returns unconfirmed; check the state instead of replaying.",
                    properties(
                        "target" to JSONObject().put("type", "string").put("enum", JSONArray(io.github.mangi.eta.agent.tool.SystemStateControls.targets)),
                        "enabled" to boolean("true to enable, false to disable"),
                        "user_id" to userId(),
                    ),
                    "target", "enabled",
                ),
            )
            .put(
                function(
                    "app_state_control",
                    "Stop, freeze, or unfreeze an exact package name in the Android user Eta belongs to, including system apps, and read back the stopped/enabled state. Only verified=true means the target state is confirmed; does not act across users or work profiles.",
                    properties(
                        "package_name" to string("Exact Android package name", 255),
                        "action" to enumString("Action", "force_stop", "freeze", "unfreeze"),
                        "user_id" to userId(),
                    ),
                    "package_name", "action",
                ),
            )
    }

    private fun emptyFunction(name: String, description: String): JSONObject =
        function(name, description, properties())

    private fun limitFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            properties("limit" to integer("Maximum number to return, default 10", 1, 30)),
        )

    private fun searchFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            if (name in setOf("search_notes", "search_system_memories", "search_calendar_events", "search_media", "search_files")) {
                AgentPersonalSearchToolCatalog.searchProperties().also { properties ->
                    if (name == "search_notes") properties.put("current_only", boolean("Use only the current raw note source; do not fall back to the historical index"))
                    if (name in setOf("search_media", "search_files")) properties.put("match", enumString("name searches current file names; content searches the historical content index", "name", "content"))
                }
            } else properties("query" to string("Optional keyword, at most 200 characters", 200), "limit" to integer("Maximum number to return, default 10", 1, 30)),
        )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name = name,
            description = description,
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .also { schema ->
                    if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
                },
        )

    private fun properties(vararg entries: Pair<String, JSONObject>): JSONObject =
        JSONObject().also { target -> entries.forEach { (name, schema) -> target.put(name, schema) } }

    private fun string(description: String, maxLength: Int? = null): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .also { schema -> maxLength?.let { schema.put("maxLength", it) } }

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun userId(): JSONObject =
        integer("Android user number, default the user Eta belongs to; other users or work profiles return USER_SCOPE_UNSUPPORTED", 0, Int.MAX_VALUE)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("minimum", minimum)
            .put("maximum", maximum)
            .put("description", description)

    private fun enumString(description: String, vararg values: String): JSONObject =
        string(description).put("enum", JSONArray(values.toList()))

    private fun stringArray(description: String, vararg values: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("items", enumString(description, *values))
            .put("uniqueItems", true)
            .put("maxItems", 7)
            .put("description", description)
}
