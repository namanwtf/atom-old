package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Generic capability mapping file paths to model visual input, independent of any personal-data provider. */
internal object AgentFileVisionToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "read_image",
                description = "Read one image at the user-specified path or a system gallery URI and provide it to the model as visual input. Call at most once per turn; when viewing multiple images, wait for the current image to return and be observed, then read the next one in the following turn.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "path",
                            JSONObject()
                                .put("type", "string")
                                .put("maxLength", 1_024)
                                .put("description", "Any absolute image path, file URI, or system gallery content URI; local paths are read by Root."),
                        ),
                    )
                    .put("required", JSONArray(listOf("path"))),
            ),
        )
    }
}
