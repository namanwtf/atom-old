package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Tool schemas for text input, waiting, and system operations. */
internal object AgentTextSystemToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "type_text",
                    description = "Write text into an input field; the result is read back for verification. Default mode=replace replaces the entire field with text, and empty text clears it;" +
                        "mode=append inserts at the current cursor. Specify index to write into that editable node without tapping it first; omit it to write into the current input focus." +
                        "submit=true presses the IME enter key after writing, for search or send; if the result is submitted=false the text was written but not submitted, so tap the send button instead without retyping." +
                        "Chinese, emoji, and long text are passed directly, without using the clipboard.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string").put("maxLength", 4_000))
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("replace").put("append"))
                                        .put("description", "replace replaces the entire field (default); append inserts at the cursor."),
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Optional editable node index; only usable with replace, and must be passed together with the observation_id from the same observation."),
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when index is specified; from the same most recent observation as index."),
                                )
                                .put(
                                    "submit",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Press the IME enter key to submit after writing; default false."),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "set_clipboard",
                    description = "Write text to the system clipboard. Suitable for preparing long text, Chinese, emoji, or special characters for pasting.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "get_clipboard",
                    description = "Read the system clipboard text. Android version or background restrictions may cause read failure.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "press_key",
                    description = "Press a system key or global action. BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS prefer the accessibility global action; ENTER prefers the IME enter key.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "button",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("BACK")
                                                .put("HOME")
                                                .put("ENTER")
                                                .put("RECENTS")
                                                .put("PASTE")
                                                .put("NOTIFICATIONS")
                                                .put("QUICK_SETTINGS")
                                        )
                                )
                        )
                        .put("required", JSONArray().put("button"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait",
                    description = "Wait for a while to let animations, network loading, or page transitions finish. Do not use it in place of the verifiable wait of wait_for_text/wait_for_package.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Wait duration, 100 to 30000, default 1000.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_text",
                    description = "Wait until the specified text or description appears on the current screen; suitable for confirming a page has arrived, a list has finished loading, or a dialog has appeared after a tap.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum wait time, 500 to 60000, default 10000.")
                                )
                                .put(
                                    "include_desc",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether to match content-desc; default true.")
                                )
                                .put(
                                    "match",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("contains").put("exact").put("prefix").put("regex"))
                                        .put("description", "Matching mode; default contains.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_package",
                    description = "Wait until the specified Android package comes to the foreground; suitable for confirming the target app has opened after launch_app/open_uri.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("package_name", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum wait time, 500 to 60000, default 10000.")
                                )
                        )
                        .put("required", JSONArray().put("package_name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_system_panel",
                    description = "Open the notification shade or quick settings panel.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "panel",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("notifications").put("quick_settings"))
                                )
                        )
                        .put("required", JSONArray().put("panel"))
                )
            )
    }
}
