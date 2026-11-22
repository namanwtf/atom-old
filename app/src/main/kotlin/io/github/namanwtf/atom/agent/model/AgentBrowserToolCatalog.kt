package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "Operate Atom's shared off-screen Agent browser without switching to an external browser. Each call executes only one action; web browsing typically starts with navigate, then uses get_readable to extract body text or find_elements to find interactive elements. Use open_uri when a URI must be handed explicitly to an external app.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "The single browser action to execute in this call.")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("navigate")
                                            .put("get_readable")
                                            .put("get_text")
                                            .put("find_elements")
                                            .put("click")
                                            .put("type")
                                            .put("scroll")
                                            .put("screenshot")
                                            .put("get_page_info")
                                            .put("go_back")
                                            .put("go_forward")
                                            .put("reload")
                                            .put("wait_for_selector")
                                    )
                            )
                            .put(
                                "url",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "The URL for navigate to visit.")
                            )
                            .put(
                                "selector",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "CSS selector used by click, type, get_text, find_elements, or wait_for_selector.")
                            )
                            .put(
                                "text",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "The text for type to input. It is sent only to the tool and not shown in the run summary.")
                            )
                            .put(
                                "submit",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "Whether to submit the containing form after type inputs; default false.")
                            )
                            .put(
                                "coordinate_x",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "Viewport X coordinate for click or type, used together with coordinate_y.")
                            )
                            .put(
                                "coordinate_y",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "Viewport Y coordinate for click or type, used together with coordinate_x.")
                            )
                            .put(
                                "amount",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "The pixel amount for scroll.")
                            )
                            .put(
                                "direction",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("up").put("down"))
                                    .put("description", "The scrolling direction for scroll.")
                            )
                            .put(
                                "offset",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "Text start offset for get_readable or get_text; default 0.")
                            )
                            .put(
                                "max_chars",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "Maximum number of text characters returned by get_readable or get_text.")
                            )
                            .put(
                                "read_image",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "Whether to attach the screenshot to the model for direct viewing during screenshot; default true.")
                            )
                            .put(
                                "timeout_ms",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "Timeout in milliseconds for navigate or wait_for_selector.")
                            )
                    )
                    .put("required", JSONArray().put("action"))
            )
        )
    }

}
