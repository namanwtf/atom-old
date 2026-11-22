package io.github.mangi.eta.agent.model

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AppStrings
import org.json.JSONObject

/**
 * Result summaries for personal-data retrieval and memory tools.
 * Presents natural, fluent conclusions to the user, never exposing private content or piling up machine metrics like raw byte or line counts.
 */
internal object AgentPersonalResultFormatter {
    fun summarizeArguments(toolName: String, argumentsJson: String): String? = runCatching {
        val args = JSONObject(argumentsJson)
        val isCharacter = toolName.startsWith("character_")
        when (toolName) {
            "memory_get", "character_memory_get" -> {
                val hasQuery = args.optString("query").trim().isNotEmpty()
                when {
                    isCharacter && hasQuery ->
                        AppStrings.get(R.string.trace_personal_arg_search_story_memory, "Search story memory")
                    isCharacter ->
                        AppStrings.get(R.string.trace_personal_arg_read_story_memory, "Read story memory")
                    hasQuery ->
                        AppStrings.get(R.string.trace_personal_arg_search_memory, "Search memory")
                    else ->
                        AppStrings.get(R.string.trace_personal_arg_read_memory, "Read memory")
                }
            }
            "memory_write", "character_memory_write" -> {
                when (args.optString("mode")) {
                    "append" -> if (isCharacter) {
                        AppStrings.get(R.string.trace_personal_arg_append_story_memory, "Append story memory")
                    } else {
                        AppStrings.get(R.string.trace_personal_arg_append_memory, "Append memory")
                    }
                    "clear" -> if (isCharacter) {
                        AppStrings.get(R.string.trace_personal_arg_clear_story_memory, "Clear story memory")
                    } else {
                        AppStrings.get(R.string.trace_personal_arg_clear_memory, "Clear memory")
                    }
                    else -> if (isCharacter) {
                        AppStrings.get(R.string.trace_personal_arg_update_story_memory, "Update story memory")
                    } else {
                        AppStrings.get(R.string.trace_personal_arg_update_memory, "Update memory")
                    }
                }
            }
            else -> null
        }
    }.getOrNull()

    fun summarize(toolName: String, json: JSONObject): String? {
        if (json.opt("ok") == false || json.optString("status") in setOf("unconfirmed", "failed")) return null

        when (toolName) {
            "memory_get", "character_memory_get" -> return summarizeMemoryGet(toolName, json)
            "memory_write", "character_memory_write" -> return summarizeMemoryWrite(toolName, json)
            "read_sms_code" -> {
                val count = itemCount(json)
                return if (count != null && count > 0) {
                    AppStrings.get(R.string.trace_personal_sms_code_read, "Read verification code")
                } else {
                    AppStrings.get(R.string.trace_personal_sms_code_empty, "No recent verification code")
                }
            }
            "read_personal_item" -> return AppStrings.get(R.string.trace_personal_item_read, "Read details")
            "summarize_bills" -> return AppStrings.get(R.string.trace_personal_bills_summarized, "Summarized bills")
        }

        val target = SEARCH_TARGETS[toolName] ?: return null
        val count = itemCount(json) ?: return null
        return if (count == 0) {
            AppStrings.get(target.emptyRes, target.emptyFallback)
        } else {
            val base = AppStrings.quantity(
                target.foundPluralsRes,
                count,
                target.foundFallback,
                target.foundFallback,
                count,
            )
            val suffix = buildList {
                if (json.optBoolean("has_more") || json.optBoolean("truncated")) {
                    add(AppStrings.get(R.string.trace_res_has_more, "More"))
                }
                if (json.optBoolean("partial") || json.optBoolean("scan_limited")) {
                    add(AppStrings.get(R.string.trace_res_partial_scope, "Limited scope"))
                }
            }.joinToString(" · ")
            if (suffix.isNotEmpty()) "$base · $suffix" else base
        }
    }

    private fun summarizeMemoryGet(toolName: String, json: JSONObject): String {
        val isCharacter = toolName.startsWith("character_")
        val matchedLines = json.optInt("matched_lines", -1)
        if (matchedLines >= 0) {
            return if (matchedLines > 0) {
                if (isCharacter) {
                    AppStrings.quantity(
                        R.plurals.trace_personal_story_found,
                        matchedLines,
                        "Found %1\$d related story entries",
                        "Found %1\$d related story entries",
                        matchedLines,
                    )
                } else {
                    AppStrings.quantity(
                        R.plurals.trace_personal_memory_found,
                        matchedLines,
                        "Found %1\$d related memories",
                        "Found %1\$d related memories",
                        matchedLines,
                    )
                }
            } else {
                if (isCharacter) {
                    AppStrings.get(R.string.trace_personal_story_empty, "No related story found")
                } else {
                    AppStrings.get(R.string.trace_personal_memory_empty, "No related memory found")
                }
            }
        }
        val hasMore = json.optBoolean("has_more")
        return when {
            isCharacter && hasMore ->
                AppStrings.get(R.string.trace_personal_story_read_prefix, "Read first part of story memory")
            isCharacter ->
                AppStrings.get(R.string.trace_personal_story_read, "Read story memory")
            hasMore ->
                AppStrings.get(R.string.trace_personal_memory_read_prefix, "Read first part of memory")
            else ->
                AppStrings.get(R.string.trace_personal_memory_read, "Read memory")
        }
    }

    private fun summarizeMemoryWrite(toolName: String, json: JSONObject): String {
        val isCharacter = toolName.startsWith("character_")
        return when (json.optString("mode")) {
            "clear" -> if (isCharacter) {
                AppStrings.get(R.string.trace_personal_story_cleared, "Cleared story memory")
            } else {
                AppStrings.get(R.string.trace_personal_memory_cleared, "Cleared memory")
            }
            "append" -> if (isCharacter) {
                AppStrings.get(R.string.trace_personal_story_saved, "Saved story memory")
            } else {
                AppStrings.get(R.string.trace_personal_memory_saved, "Saved memory")
            }
            else -> if (isCharacter) {
                AppStrings.get(R.string.trace_personal_story_updated, "Updated story memory")
            } else {
                AppStrings.get(R.string.trace_personal_memory_updated, "Updated memory")
            }
        }
    }

    private fun itemCount(json: JSONObject): Int? =
        (json.optJSONArray("items") ?: json.optJSONArray("results"))?.length()
            ?: (json.opt("count") as? Number)?.toInt()?.takeIf { it >= 0 }

    private data class SearchTarget(
        @PluralsRes val foundPluralsRes: Int,
        val foundFallback: String,
        @StringRes val emptyRes: Int,
        val emptyFallback: String,
    )

    private val SEARCH_TARGETS = mapOf(
        "search_system_memories" to SearchTarget(
            R.plurals.trace_personal_found_system_memories,
            "Found %1\$d system memories",
            R.string.trace_personal_empty_system_memories,
            "No related system memories found",
        ),
        "search_coloros_memories" to SearchTarget(
            R.plurals.trace_personal_found_system_memories,
            "Found %1\$d system memories",
            R.string.trace_personal_empty_system_memories,
            "No related system memories found",
        ),
        "search_notes" to SearchTarget(
            R.plurals.trace_personal_found_notes,
            "Found %1\$d notes",
            R.string.trace_personal_empty_notes,
            "No related notes found",
        ),
        "search_coloros_notes" to SearchTarget(
            R.plurals.trace_personal_found_notes,
            "Found %1\$d notes",
            R.string.trace_personal_empty_notes,
            "No related notes found",
        ),
        "search_calendar_events" to SearchTarget(
            R.plurals.trace_personal_found_calendar_events,
            "Found %1\$d events",
            R.string.trace_personal_empty_calendar_events,
            "No related events found",
        ),
        "search_contacts" to SearchTarget(
            R.plurals.trace_personal_found_contacts,
            "Found %1\$d contacts",
            R.string.trace_personal_empty_contacts,
            "No matching contacts found",
        ),
        "search_messages" to SearchTarget(
            R.plurals.trace_personal_found_messages,
            "Found %1\$d messages",
            R.string.trace_personal_empty_messages,
            "No related messages found",
        ),
        "search_call_history" to SearchTarget(
            R.plurals.trace_personal_found_call_history,
            "Found %1\$d call records",
            R.string.trace_personal_empty_call_history,
            "No call records found",
        ),
        "search_saved_places" to SearchTarget(
            R.plurals.trace_personal_found_saved_places,
            "Found %1\$d places",
            R.string.trace_personal_empty_saved_places,
            "No related places found",
        ),
        "search_personal_orders" to SearchTarget(
            R.plurals.trace_personal_found_personal_orders,
            "Found %1\$d orders",
            R.string.trace_personal_empty_personal_orders,
            "No related orders found",
        ),
        "search_bills" to SearchTarget(
            R.plurals.trace_personal_found_bills,
            "Found %1\$d bills",
            R.string.trace_personal_empty_bills,
            "No bill records found",
        ),
        "search_todos" to SearchTarget(
            R.plurals.trace_personal_found_todos,
            "Found %1\$d todos",
            R.string.trace_personal_empty_todos,
            "No todos found",
        ),
        "search_calendar_todos" to SearchTarget(
            R.plurals.trace_personal_found_todos,
            "Found %1\$d todos",
            R.string.trace_personal_empty_todos,
            "No todos found",
        ),
        "search_flights" to SearchTarget(
            R.plurals.trace_personal_found_flights,
            "Found %1\$d flights",
            R.string.trace_personal_empty_flights,
            "No flight trips found",
        ),
        "search_hotels" to SearchTarget(
            R.plurals.trace_personal_found_hotels,
            "Found %1\$d hotel bookings",
            R.string.trace_personal_empty_hotels,
            "No bookings found",
        ),
        "search_trains" to SearchTarget(
            R.plurals.trace_personal_found_trains,
            "Found %1\$d train trips",
            R.string.trace_personal_empty_trains,
            "No train trips found",
        ),
        "search_daily_events" to SearchTarget(
            R.plurals.trace_personal_found_daily_events,
            "Found %1\$d life events",
            R.string.trace_personal_empty_daily_events,
            "No life events found",
        ),
        "search_memory_collections" to SearchTarget(
            R.plurals.trace_personal_found_memory_collections,
            "Found %1\$d memory collections",
            R.string.trace_personal_empty_memory_collections,
            "No memory collections found",
        ),
        "search_recordings" to SearchTarget(
            R.plurals.trace_personal_found_recordings,
            "Found %1\$d recordings",
            R.string.trace_personal_empty_recordings,
            "No recordings found",
        ),
        "search_coloros_recordings" to SearchTarget(
            R.plurals.trace_personal_found_recordings,
            "Found %1\$d recordings",
            R.string.trace_personal_empty_recordings,
            "No recordings found",
        ),
        "search_recording_summaries" to SearchTarget(
            R.plurals.trace_personal_found_recording_summaries,
            "Found %1\$d recording summaries",
            R.string.trace_personal_empty_recording_summaries,
            "No recording summaries found",
        ),
        "recent_notifications" to SearchTarget(
            R.plurals.trace_personal_found_notifications,
            "Found %1\$d notifications",
            R.string.trace_personal_empty_notifications,
            "No notifications found",
        ),
        "search_notification_history" to SearchTarget(
            R.plurals.trace_personal_found_notifications,
            "Found %1\$d notifications",
            R.string.trace_personal_empty_notifications,
            "No notifications found",
        ),
        "search_clipboard_history" to SearchTarget(
            R.plurals.trace_personal_found_clipboard,
            "Found %1\$d clipboard entries",
            R.string.trace_personal_empty_clipboard,
            "No clipboard entries found",
        ),
        "search_downloads" to SearchTarget(
            R.plurals.trace_personal_found_downloads,
            "Found %1\$d download records",
            R.string.trace_personal_empty_downloads,
            "No download records found",
        ),
        "search_media" to SearchTarget(
            R.plurals.trace_personal_found_media,
            "Found %1\$d media files",
            R.string.trace_personal_empty_media,
            "No related media files found",
        ),
        "search_audio" to SearchTarget(
            R.plurals.trace_personal_found_audio,
            "Found %1\$d audio files",
            R.string.trace_personal_empty_audio,
            "No related audio files found",
        ),
        "search_files" to SearchTarget(
            R.plurals.trace_personal_found_files,
            "Found %1\$d files",
            R.string.trace_personal_empty_files,
            "No related files found",
        ),
        "search_qq_chat_images" to SearchTarget(
            R.plurals.trace_personal_found_chat_images,
            "Found %1\$d chat images",
            R.string.trace_personal_empty_chat_images,
            "No chat images found",
        ),
        "search_wechat_chat_images" to SearchTarget(
            R.plurals.trace_personal_found_chat_images,
            "Found %1\$d chat images",
            R.string.trace_personal_empty_chat_images,
            "No chat images found",
        ),
    )
}
