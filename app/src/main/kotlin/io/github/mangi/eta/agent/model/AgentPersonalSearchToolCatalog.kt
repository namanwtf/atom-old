package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.context.PersonalSearchTools
import org.json.JSONArray
import org.json.JSONObject

internal object AgentPersonalSearchToolCatalog {
    fun appendTo(tools: JSONArray) {
        PersonalSearchTools.searches.forEach { spec ->
            tools.put(
                function(
                    spec.name,
                    spec.description + "Results come from a historical index and may be delayed; the returned ref can read details.",
                    searchProperties(),
                )
            )
        }
        tools.put(
            function(
                "read_personal_item",
                "Use the ref returned by a personal data search to read index details; this reference cannot directly modify the original record.",
                JSONObject().put("ref", string("The ref from the search result.", 160)),
                "ref",
            )
        )
        val summary =
            searchProperties().also {
                it.remove("limit")
                it.remove("offset")
                it.remove("sort")
            }
        tools.put(
            function(
                "summarize_bills",
                "Precisely aggregate the matching bill index, distinguishing income, expense, transfer, and unknown types; fully aggregates at most 1000 entries, and fails rather than returning a partial total when the range is too large.",
                summary,
            )
        )
    }

    fun searchProperties(): JSONObject =
        JSONObject()
            .put("query", string("Optional keyword", 200))
            .put("start_time", string("Inclusive start time, ISO 8601 with time-zone offset", 64))
            .put("end_time", string("Exclusive end time, ISO 8601 with time-zone offset", 64))
            .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 30))
            .put(
                "offset",
                JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 10000),
            )
            .put(
                "sort",
                JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("newest", "oldest"))),
            )

    fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name,
            description,
            JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(required.toList()))
                .put("additionalProperties", false),
        )

    private fun string(description: String, max: Int) =
        JSONObject().put("type", "string").put("description", description).put("maxLength", max)
}
