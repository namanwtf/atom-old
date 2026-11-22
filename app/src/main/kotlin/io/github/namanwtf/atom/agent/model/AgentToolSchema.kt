package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentToolSchema {
    fun coordinateSpace(): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("normalized").put("screen").put("screenshot"))
            .put(
                "description",
                "Required. normalized (recommended): x and y range from 0–999 as a proportion of the full screen; use it when reading a screenshot, unaffected by image scaling;" +
                    "screen: real screen pixels, which is what ui_nodes center/bounds use;" +
                    "screenshot: raw pixels of the most recent attached image; use only when you are sure you are viewing the unscaled original.",
            )

    fun function(
        name: String,
        description: String,
        parameters: JSONObject,
    ): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
}
