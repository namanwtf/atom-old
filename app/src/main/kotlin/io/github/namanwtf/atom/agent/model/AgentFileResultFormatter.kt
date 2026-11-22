package io.github.namanwtf.atom.agent.model

import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.core.AppStrings
import java.util.Locale
import org.json.JSONObject

/** File traces retain only the operation result and scale, never carrying paths, content, or search terms into the persistent summary. */
internal object AgentFileResultFormatter {
    fun summarize(toolName: String, json: JSONObject): String? = when (toolName) {
        "write_file" -> summarizeWrite(json)
        "read_file" -> summarizeRead(json)
        "stat_file" -> summarizeStat(json)
        "edit_file" -> json.nonNegativeLong("replacements")?.let {
            AppStrings.quantity(
                R.plurals.trace_res_replacements,
                it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                "Replaced %1\$d occurrences",
                "Replaced %1\$d occurrences",
                it,
            )
        }
        "list_directory" -> json.optJSONArray("entries")?.let { entries ->
            val count = entries.length()
            if (count == 0) AppStrings.get(R.string.trace_file_dir_empty, "Directory is empty")
            else withScope(
                AppStrings.quantity(
                    R.plurals.trace_file_dir_listed,
                    count,
                    "Listed %1\$d items",
                    "Listed %1\$d items",
                    count,
                ),
                json,
                AppStrings.get(R.string.trace_res_has_more, "More"),
            )
        }
        "glob_files", "grep_files" -> json.optJSONArray("matches")?.let { matches ->
            val count = matches.length()
            val label = when {
                count == 0 && toolName == "glob_files" ->
                    AppStrings.get(R.string.trace_file_no_files, "No matching files")
                count == 0 -> AppStrings.get(R.string.trace_res_no_matches, "No matches found")
                toolName == "glob_files" -> AppStrings.quantity(
                    R.plurals.trace_file_found_files,
                    count,
                    "Found %1\$d files",
                    "Found %1\$d files",
                    count,
                )
                else -> AppStrings.quantity(
                    R.plurals.trace_res_found_matches,
                    count,
                    "Found %1\$d matches",
                    "Found %1\$d matches",
                    count,
                )
            }
            if (count == 0) {
                if (json.opt("partial") == true) {
                    "$label · ${AppStrings.get(R.string.trace_file_scan_limited, "Limited scan scope")}"
                } else {
                    label
                }
            } else {
                withScope(label, json, AppStrings.get(R.string.trace_res_has_more, "More"))
            }
        }
        else -> null
    }

    private fun summarizeWrite(json: JSONObject): String? {
        val bytes = json.nonNegativeLong("bytes_written") ?: return null
        val formatted = formatBytes(bytes)
        return when (json.optString("mode")) {
            "append" -> AppStrings.get(R.string.trace_file_appended, "Appended %1\$s", formatted)
            "overwrite" -> AppStrings.get(R.string.trace_file_written, "Wrote %1\$s", formatted)
            else -> null
        }
    }

    private fun summarizeRead(json: JSONObject): String? {
        val bytes = json.nonNegativeLong("bytes_read") ?: return null
        if (json.opt("start_line_reached") == false) {
            return when (json.opt("truncated") as? Boolean) {
                true -> AppStrings.get(
                    R.string.trace_file_line_not_reached_more,
                    "Target line not reached · can continue reading",
                )
                false -> AppStrings.get(
                    R.string.trace_file_line_not_reached_eof,
                    "Target line not reached · reached end of file",
                )
                null -> AppStrings.get(R.string.trace_file_line_not_reached, "Target line not reached")
            }
        }
        val isTruncated = json.opt("truncated") == true || json.opt("has_more") == true
        val formatted = formatBytes(bytes)
        val label = if (isTruncated) {
            AppStrings.get(R.string.trace_res_read_prefix, "Read first %1\$s", formatted)
        } else {
            AppStrings.get(R.string.trace_file_read, "Read %1\$s", formatted)
        }
        return withScope(label, json, AppStrings.get(R.string.trace_res_has_more, "More"))
    }

    private fun summarizeStat(json: JSONObject): String? {
        val file = json.optJSONObject("file") ?: return null
        return when (file.optString("kind")) {
            "file" -> file.nonNegativeLong("size_bytes")?.let {
                AppStrings.get(R.string.trace_file_kind_file_size, "File · %1\$s", formatBytes(it))
            }
            "directory" -> AppStrings.get(R.string.trace_file_kind_dir, "Directory")
            "symlink" -> AppStrings.get(R.string.trace_file_kind_symlink, "Symbolic link")
            "other" -> AppStrings.get(R.string.trace_file_kind_other, "Other file type")
            else -> null
        }
    }

    private fun withScope(label: String, json: JSONObject, continuation: String): String =
        buildList {
            add(label)
            val hasMore = json.opt("truncated") == true || json.opt("has_more") == true
            if (hasMore) add(continuation)
            if (json.opt("partial") == true) {
                add(AppStrings.get(R.string.trace_file_scan_limited, "Limited scan scope"))
            }
        }.joinToString(" · ")

    private fun JSONObject.nonNegativeLong(key: String): Long? =
        (opt(key) as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) {
            return AppStrings.quantity(
                R.plurals.trace_unit_bytes,
                bytes.toInt(),
                "%1\$d bytes",
                "%1\$d bytes",
                bytes,
            )
        }
        val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
        var size = bytes / 1024.0
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return "${String.format(Locale.ROOT, "%.1f", size).removeSuffix(".0")} ${units[unit]}"
    }
}
