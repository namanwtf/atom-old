package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Tool schemas for context, app entry points, and screen observation. */
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "Get the phone's current time, time zone, and most recent system location; call this when the task involves now, today, tomorrow, or current location.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "Search installed Android apps on the phone, returning app names and package names. Call this tool first before opening an app if you are unsure of its package name.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "App name or package name fragment, e.g. QQ, WeChat, com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether to include system apps; default false.")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "At most 1 to 20 results, default 10.")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "Launch an installed Android app. Prefer providing package_name; an app name alone allows fuzzy matching, and multiple matches return candidates without launching. ok=true means the launch request was submitted; launch_status=window_observed means the app window was observed on the target canvas, and when unconfirmed, confirm via observe_screen first instead of relaunching directly.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Exact Android package name, e.g. com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "App display name, e.g. QQ")
                                )
                                .put(
                                    "display",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("auto").put("virtual").put("main"))
                                        .put("default", "auto")
                                        .put(
                                            "description",
                                            "Target canvas to launch: auto (default; cross-app tasks run preferentially on the background virtual screen without interrupting the user's current main screen), virtual (explicitly launch on the background virtual screen), main (explicitly open in the foreground on the physical main screen)."
                                        )
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "present_to_user",
                    description = "Seamlessly enlarge and hand over a ready app screen from the background virtual screen to the physical main screen foreground, for the user to view directly, complete verification, or confirm payment. Call this when the task has finished prerequisite operations in the virtual screen, reached the checkout/QR code/result page, or hit a security window that requires the user's own action.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "reason",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Optional short reason shown to the user for presenting the screen, e.g. "reached the payment confirmation page".")
                                )
                        )
                        .put("additionalProperties", false),
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "Explicitly hand a definitely-valid URI to an external Android app, such as https, tel, geo, or an app deep link. It is not for reading web pages or web interaction. Do not fabricate a URI.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "A definitely-valid URI that the system can handle.")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "Take control of the phone and observe the current screen; Runtime waits if another session holds it. By default returns only the foreground app, screen size, observation_id, and visible UI nodes, without a screenshot. When nodes are empty, the target cannot be uniquely identified, the interface is primarily visual content such as Canvas/map/image/QR code, or the task depends on color, imagery, or spatial layout, explicitly set include_screenshot=true; when adding a screenshot keep include_ui_tree=true and refresh nodes and observation_id from the same new observation, never mixing a new screenshot with old nodes. Node actions must carry the observation_id of the same observation verbatim; when the tree is truncated but node semantics are still valid, prefer raising max_nodes to 120 and retry. Call release_gui after the phone-operation phase completes to let other sessions continue; after release or user takeover you must re-observe before operating.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_SCREENSHOT)
                                        .put("description", "Whether to attach the current screen's raw image for the model; default false. Enable explicitly only when UI nodes are insufficient to complete the task.")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_UI_TREE)
                                        .put("description", "Whether to return the UI node list; default true.")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentScreenObservationContract.MIN_MAX_NODES)
                                        .put("maximum", AgentScreenObservationContract.MAX_MAX_NODES)
                                        .put("default", AgentScreenObservationContract.DEFAULT_MAX_NODES)
                                        .put("description", "At most 1 to 120 UI nodes returned, default 60.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "release_gui",
                    description = "End the current phone-operation phase and release control so other sessions can use the phone. Pure thinking, web, Shell, or file work does not need to keep holding the phone. After release, old observations, nodes, and coordinates are all invalid; call observe_screen again before operating. Calling it without holding control also succeeds.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                        .put("additionalProperties", false),
                )
            )
    }
}
