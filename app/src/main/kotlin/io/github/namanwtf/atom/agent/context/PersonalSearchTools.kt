package io.github.namanwtf.atom.agent.context

import androidx.annotation.StringRes
import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

/** Model-facing domain names; index resources and protocols are mapped only here and never become public tool parameters. */
internal object PersonalSearchTools {
    data class Search(
        val name: String,
        val source: String,
        @StringRes private val titleRes: Int,
        private val titleFallback: String,
        @StringRes private val descriptionRes: Int,
        private val descriptionFallback: String,
    ) {
        val title: String
            get() = AppStrings.get(titleRes, titleFallback)

        val description: String
            get() = AppStrings.get(descriptionRes, descriptionFallback)
    }

    val searches =
        listOf(
            Search(
                name = "search_bills",
                source = "bills",
                titleRes = R.string.tool_cat_search_bills_title,
                titleFallback = "Search bills",
                descriptionRes = R.string.tool_cat_search_bills_desc,
                descriptionFallback = "Find bill records by merchant, content, and time.",
            ),
            Search(
                name = "search_todos",
                source = "todos",
                titleRes = R.string.tool_cat_search_todos_title,
                titleFallback = "Search to-do leads",
                descriptionRes = R.string.tool_cat_search_todos_desc,
                descriptionFallback = "Search to-do leads extracted from personal information; records without a recognized time cannot be matched by time.",
            ),
            Search(
                name = "search_calendar_todos",
                source = "calendar_todos",
                titleRes = R.string.tool_cat_search_calendar_todos_title,
                titleFallback = "Search calendar to-dos",
                descriptionRes = R.string.tool_cat_search_calendar_todos_desc,
                descriptionFallback = "Search to-dos in the calendar and their scheduled times.",
            ),
            Search(
                name = "search_memory_collections",
                source = "collections",
                titleRes = R.string.tool_cat_search_memory_collections_title,
                titleFallback = "Search memory collections",
                descriptionRes = R.string.tool_cat_search_memory_collections_desc,
                descriptionFallback = "Find saved memory collections; filtering by business time is not supported.",
            ),
            Search(
                name = "search_daily_events",
                source = "events",
                titleRes = R.string.tool_cat_search_daily_events_title,
                titleFallback = "Search life events",
                descriptionRes = R.string.tool_cat_search_daily_events_desc,
                descriptionFallback = "Find life events recorded or inferred by the system; inferences are not treated as confirmed facts.",
            ),
            Search(
                name = "search_flights",
                source = "flights",
                titleRes = R.string.tool_cat_search_flights_title,
                titleFallback = "Search flight trips",
                descriptionRes = R.string.tool_cat_search_flights_desc,
                descriptionFallback = "Retrieve trips by flight, airport, and scheduled departure time.",
            ),
            Search(
                name = "search_hotels",
                source = "hotels",
                titleRes = R.string.tool_cat_search_hotels_title,
                titleFallback = "Search hotel bookings",
                descriptionRes = R.string.tool_cat_search_hotels_desc,
                descriptionFallback = "Retrieve bookings by hotel, address, order, and check-in time.",
            ),
            Search(
                name = "search_trains",
                source = "trains",
                titleRes = R.string.tool_cat_search_trains_title,
                titleFallback = "Search train trips",
                descriptionRes = R.string.tool_cat_search_trains_desc,
                descriptionFallback = "Retrieve trips by train number, station, and departure time.",
            ),
        )
    val names =
        searches.mapTo(linkedSetOf()) { it.name } + setOf("read_personal_item", "summarize_bills")
    val aliases =
        mapOf(
            "search_coloros_notes" to "search_notes",
            "search_coloros_memories" to "search_system_memories",
        )

    fun canonical(name: String): String = aliases[name] ?: name

    fun isIndexedSearch(name: String): Boolean = searches.any { it.name == name }
}

internal class IndexedPersonalSearch(private val service: PersonalContextQueryService) {
    fun execute(name: String, arguments: JSONObject, fallback: Boolean = false): JSONObject? {
        val request = JSONObject(arguments.toString())
        val source =
            when (name) {
                "search_media" ->
                    if (request.optString("match", "name") == "content") "photos" else return null
                "search_files" ->
                    if (request.optString("match", "name") == "content") "files" else return null
                "search_notes" -> if (fallback) "notes" else return null
                "search_system_memories" ->
                    if (hasExtendedFilter(request)) "memories" else return null
                "summarize_bills" -> "bills"
                "read_personal_item" -> {
                    val reference = request.opt("ref") as? String ?: return invalid()
                    val parts = reference.split(':')
                    if (
                        parts.size != 3 ||
                            parts[0] != "atom-index" ||
                            PersonalContextSources.find(parts[1]) == null ||
                            !Regex("[0-9]{1,19}").matches(parts[2]) ||
                            parts[2].toLongOrNull() == null ||
                            request.length() != 1
                    )
                        return invalid()
                    request.remove("ref")
                    request.put("id", parts[2])
                    parts[1]
                }
                else ->
                    PersonalSearchTools.searches.firstOrNull { it.name == name }?.source
                        ?: return null
            }
        request.remove("match")
        request.remove("current_only")
        request
            .put("source", source)
            .put(
                "action",
                when (name) {
                    "read_personal_item" -> "read"
                    "summarize_bills" -> "bill_summary"
                    else -> "search"
                },
            )
        return service.execute(request).put("tool", name).also { result ->
            val items = result.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                item
                    .optString("id")
                    .takeIf { it.toLongOrNull() != null }
                    ?.let {
                        item.put("ref", "atom-index:$source:$it")
                    }
            }
            result.put("reference_scope", "Index references are for reading only; modifying the original app record requires re-verifying the original object ID")
        }
    }

    private fun hasExtendedFilter(args: JSONObject) =
        listOf("start_time", "end_time", "offset", "sort").any(args::has)

    private fun invalid() =
        JSONObject()
            .put("ok", false)
            .put("code", "INVALID_ARGUMENT")
            .put("message", "A complete ref returned by the query result must be provided")
}
