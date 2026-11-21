package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.terminal.FileToolSearch
import org.json.JSONArray
import org.json.JSONObject

internal object AgentFileToolCatalog {
    val names = setOf("read_file", "write_file", "edit_file", "stat_file", "list_directory", "glob_files", "grep_files")

    fun appendTo(tools: JSONArray) {
        tools.put(tool("read_file", "Read UTF-8 text. Known paths are read directly; the returned next_offset_bytes is the exact continuation position, and pass expected_revision when continuing. Supports start_line positioning; overly long lines can be segmented, and binaries should use the corresponding media tool.", properties()
            .put("path", path())
            .put("offset_bytes", integer("Byte start position, default 0; for continuation use the previous next_offset_bytes, do not recompute it yourself.", 0))
            .put("max_bytes", integer("Per-call text byte budget, default 16000; larger values are accepted for backward compatibility but at most 16000 bytes are actually returned.", 4, 262144))
            .put("start_line", integer("Optional 1-based starting line number; when reading by line, offset_bytes should be omitted or 0. Long-distance positioning is limited by the scan budget; check start_line_reached.", 1))
            .put("max_lines", integer("Maximum number of lines to return, default 200; an overly long single line is also limited by the byte budget.", 1, 2000))
            .put("expected_revision", revision()), listOf("path")))
        tools.put(tool("write_file", "Create, fully overwrite, or append a UTF-8 file; parent directories are created. Use edit_file for partial edits. expected_revision prevents overwriting a file that has changed; atomic indicates whether the backend replaces atomically.", properties()
            .put("path", path()).put("content", text("Full file content or appended content, at most 512 KiB after UTF-8 encoding.", 524288))
            .put("append", JSONObject().put("type", "boolean").put("default", false))
            .put("expected_revision", revision()), listOf("path", "content")))
        tools.put(tool("edit_file", "Precisely replace old_text in a UTF-8 file of at most 512 KiB. By default it must match uniquely; when not found or matched multiple times nothing is written - re-read or add more context. Re-check the old content before writing; atomic does not mean mutually exclusive with external programs.", properties()
            .put("path", path()).put("old_text", text("Must exactly match the old content in the file and must not be empty.", 524288).put("minLength", 1))
            .put("new_text", text("Replacement content; an empty string deletes the matched fragment.", 524288))
            .put("replace_all", JSONObject().put("type", "boolean").put("default", false))
            .put("expected_revision", revision()), listOf("path", "old_text", "new_text")))
        tools.put(tool("stat_file", "Query the canonical path, type, size, and revision of a known file or directory without reading its content.", properties()
            .put("path", path()), listOf("path")))
        tools.put(tool("list_directory", "Page through direct children, returning structured entries and next_offset. For the next page use the same path, next_offset, and expected_revision; has_more=false means enumeration is complete.", properties()
            .put("path", path()).put("show_hidden", JSONObject().put("type", "boolean").put("default", false))
            .put("limit", integer("Number of entries per raw directory page, default 80; may be fewer after hidden items are filtered.", 1, 200))
            .put("offset", integer("Directory enumeration position, default 0; use next_offset for the next page.", 0))
            .put("expected_revision", revision()), emptyList()))
        tools.put(tool("glob_files", "Recursively find files within the working directory by path pattern. Supports *, ?, **; skips hidden directories by default and does not follow directory links. Results are bounded; use the unchanged cursor to continue, and when partial=true you must not claim all files were searched.", properties()
            .put("path", path()).put("pattern", text("Pattern relative to the search directory, e.g. **/*.json.", 500).put("minLength", 1))
            .put("limit", integer("Maximum number of entries to return, default 80.", 1, 200))
            .put("cursor", text("Opaque continuation cursor returned by the previous query; keep all query conditions unchanged.", FileToolSearch.MAX_CURSOR_CHARS))
            .put("include_hidden", JSONObject().put("type", "boolean").put("default", false)), listOf("pattern")))
        tools.put(tool("grep_files", "Search literal text in UTF-8 text within the working directory, returning path, line number, and matched line. Case-sensitive by default; does not depend on system rg. Scans in segments bounded by entry and byte budgets, resumable via cursor; binary and inaccessible items are skipped and reported as an incomplete range.", properties()
            .put("path", path()).put("query", text("The literal text to find, not a regular expression.", 500).put("minLength", 1))
            .put("glob", text("File pattern relative to the directory, default **/*.", 500))
            .put("limit", integer("Maximum number of matches to return, default 50.", 1, 200))
            .put("cursor", text("Opaque continuation cursor returned by the previous query; keep all query conditions unchanged.", FileToolSearch.MAX_CURSOR_CHARS))
            .put("case_sensitive", JSONObject().put("type", "boolean").put("default", true))
            .put("include_hidden", JSONObject().put("type", "boolean").put("default", false)), listOf("query")))
    }

    private fun properties(): JSONObject = JSONObject()
        .put("environment", JSONObject().put("type", "string").put("enum", JSONArray(listOf("android", "linux")))
            .put("description", "Environment the file path belongs to (default android): pass linux explicitly when reading, writing, or searching paths inside the Linux environment (e.g. /workspace, /workspace/mounts/<name>, /root); pass android when using an Android host path. Do not use a host rootfs path in place of a guest path."))
        .put("identity", JSONObject().put("type", "string").put("enum", JSONArray(listOf("user", "root")))
            .put("description", "Optional host execution identity; defaults to root when Android root authorization is confirmed, otherwise user, same as terminal. Linux resolves by the selected backend: PRoot uses user, chroot uses root."))
        .put("cwd", text("Optional working directory; when omitted, shares the current environment's default workspace with terminal; see the current run context for the actual path.", 4096))

    private fun path(): JSONObject = text("Absolute path or path relative to cwd; only file system paths are accepted, not content URIs. When using the path of an imported file, you operate on that copy.", 4096)
    private fun revision(): JSONObject = text("The revision from the previous file or directory result; versions from different environments and identities must not be mixed.", 256)
    private fun text(description: String, max: Int): JSONObject = JSONObject().put("type", "string").put("description", description).put("maxLength", max)
    private fun integer(description: String, minimum: Int, maximum: Int? = null): JSONObject =
        JSONObject().put("type", "integer").put("description", description).put("minimum", minimum)
            .also { if (maximum != null) it.put("maximum", maximum) }

    private fun tool(name: String, description: String, properties: JSONObject, required: List<String>): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", properties)
            .put("additionalProperties", false).put("required", JSONArray(required))
        return AgentToolSchema.function(name, description, schema)
    }
}
