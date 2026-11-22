package io.github.mangi.eta.agent.context

import java.math.BigDecimal
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

internal class PersonalContextArgumentException(message: String) : IllegalArgumentException(message) {
    val code: String = "INVALID_ARGUMENT"
}

internal enum class PersonalContextAction(val wireName: String) {
    SOURCES("sources"), SEARCH("search"), READ("read"), COUNT("count"), BILL_SUMMARY("bill_summary");
}

internal enum class PersonalContextSort(val wireName: String) { NEWEST("newest"), OLDEST("oldest") }

internal data class PersonalContextRequest(
    val action: PersonalContextAction,
    val source: PersonalContextSource?,
    val query: String? = null,
    val start: Instant? = null,
    val end: Instant? = null,
    val limit: Int = 10,
    val offset: Int = 0,
    val sort: PersonalContextSort = PersonalContextSort.NEWEST,
    val id: String? = null,
) {
    companion object {
        const val MAX_QUERY_CHARACTERS = 200
        const val MAX_LIMIT = 30
        const val MAX_OFFSET = 10_000

        private val timePattern = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(?::[0-9]{2}(?:\\.[0-9]{1,9})?)?(?:Z|[+-][0-9]{2}:[0-9]{2})$")
        private val idPattern = Regex("^[0-9]{1,19}$")

        fun parse(arguments: JSONObject): PersonalContextRequest {
            val actionName = requiredString(arguments, "action", "A valid action must be provided")
            val action = PersonalContextAction.entries.firstOrNull { it.wireName == actionName }
                ?: invalid("This personal context operation is not supported")
            val source = if (arguments.has("source")) {
                PersonalContextSources.find(requiredString(arguments, "source", "source must be a valid data source name"))
                    ?: invalid("This personal context data source is not supported")
            } else null
            if (action != PersonalContextAction.SOURCES && source == null) invalid("This operation requires a source")
            if (action == PersonalContextAction.BILL_SUMMARY && source?.id != "bills") invalid("bill_summary only supports the bills data source")

            val query = optionalString(arguments, "query", "query must be text")?.let { value ->
                if (value.codePointCount(0, value.length) > MAX_QUERY_CHARACTERS) invalid("query exceeds 200 characters")
                if ('\u0000' in value || !Charsets.UTF_8.newEncoder().canEncode(value)) invalid("query contains invalid characters")
                value.trim().takeIf(String::isNotEmpty)
            }
            val start = parseTime(arguments, "start_time")
            val end = parseTime(arguments, "end_time")
            if (start != null && end != null && start >= end) invalid("The time range must have start_time earlier than end_time")
            if ((start != null || end != null) && source?.timeField == null) invalid("This data source has no confirmed business time field and cannot be filtered by time")
            source?.timeUnit?.toBounds(start, end)
            val limit = optionalInteger(arguments, "limit", 10, 1, MAX_LIMIT)
            val offset = optionalInteger(arguments, "offset", 0, 0, MAX_OFFSET)
            val sort = optionalString(arguments, "sort", "sort must be newest or oldest")?.let { value ->
                PersonalContextSort.entries.firstOrNull { it.wireName == value } ?: invalid("sort must be newest or oldest")
            } ?: PersonalContextSort.NEWEST
            val id = if (action == PersonalContextAction.READ) {
                val value = requiredString(arguments, "id", "read requires an integer-text id")
                if (!idPattern.matches(value)) invalid("id must be a non-negative integer text of at most 19 digits")
                value.toLongOrNull()?.toString() ?: invalid("id exceeds the supported integer range")
            } else null
            return PersonalContextRequest(action, source, query, start, end, limit, offset, sort, id)
        }

        private fun parseTime(arguments: JSONObject, name: String): Instant? {
            val value = optionalString(arguments, name, "The time argument must be ISO 8601 text with a timezone offset") ?: return null
            if (!timePattern.matches(value)) invalid("The time argument must contain an ISO 8601 date, time, and Z or a timezone offset")
            return try {
                OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            } catch (_: DateTimeException) {
                invalid("The time argument is not a valid date or timezone offset")
            }
        }

        private fun optionalInteger(arguments: JSONObject, name: String, default: Int, minimum: Int, maximum: Int): Int {
            if (!arguments.has(name)) return default
            val value = arguments.opt(name)
            if (value !is Number) invalid("The pagination argument must be a JSON integer")
            val integer = try { BigDecimal(value.toString()).intValueExact() } catch (_: NumberFormatException) {
                invalid("The pagination argument must be a finite integer")
            } catch (_: ArithmeticException) {
                invalid("The pagination argument must be an integer within range")
            }
            if (integer !in minimum..maximum) invalid("The pagination argument is out of the allowed range")
            return integer
        }

        private fun optionalString(arguments: JSONObject, name: String, message: String): String? =
            if (arguments.has(name)) arguments.opt(name) as? String ?: invalid(message) else null

        private fun requiredString(arguments: JSONObject, name: String, message: String): String =
            arguments.opt(name) as? String ?: invalid(message)

        private fun invalid(message: String): Nothing = throw PersonalContextArgumentException(message)
    }
}
