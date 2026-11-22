package io.github.mangi.eta.agent.model

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.imagegen.GeneratedImageActivityPayload
import io.github.mangi.eta.agent.imagegen.ImageGenToolCatalog
import io.github.mangi.eta.agent.subagent.AgentSubagentToolCatalog
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.core.AppStrings
import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** Tool summaries are shown to the user without sensitive parameters; terminal commands are provided via a separate field for the user to review. */
internal class AgentTraceFormatter {
    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "web_search" -> AppStrings.get(R.string.trace_arg_web_search, "Search the web")
            "fetch_url" -> summarizeFetchArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            in AgentTerminalToolCatalog.names -> summarizeTerminalArguments(toolCall.name, toolCall.argumentsJson)
            "run_command" -> AppStrings.get(R.string.trace_arg_run_command_android, "Run command · Android")
            "write_file" -> summarizeTextLength(
                AppStrings.get(R.string.trace_arg_write_file, "Write file"),
                toolCall.argumentsJson,
                "content",
            )
            "read_file" -> AppStrings.get(R.string.trace_arg_read_file, "Read file")
            "list_directory" -> AppStrings.get(R.string.trace_arg_list_directory, "List directory")
            "edit_file" -> AppStrings.get(R.string.trace_arg_edit_file, "Edit file precisely")
            "stat_file" -> AppStrings.get(R.string.trace_arg_stat_file, "View file info")
            "glob_files" -> AppStrings.get(R.string.trace_arg_glob_files, "Find files by pattern")
            "grep_files" -> AppStrings.get(R.string.trace_arg_grep_files, "Search file contents")
            "inspect_app" -> AppStrings.get(R.string.trace_arg_inspect_app, "Inspect app")
            "type_text" -> summarizeTypeText(toolCall.argumentsJson)
            "input_text" -> summarizeTextLength(
                AppStrings.get(R.string.trace_arg_input_text, "Enter text"),
                toolCall.argumentsJson,
                "text",
            )
            "replace_text" -> summarizeTextLength(
                AppStrings.get(R.string.trace_arg_replace_text, "Replace text"),
                toolCall.argumentsJson,
                "text",
            )
            "paste_text", "set_clipboard" -> summarizeTextLength(
                AppStrings.get(R.string.trace_arg_paste_text, "Paste text"),
                toolCall.argumentsJson,
                "text",
            )
            "clear_text" -> AppStrings.get(R.string.trace_arg_clear_text, "Clear text")
            "get_clipboard" -> AppStrings.get(R.string.trace_arg_get_clipboard, "Read clipboard")
            "search_apps" -> summarizeQueryArguments(
                AppStrings.get(R.string.trace_arg_search_apps, "Search apps"),
                toolCall.argumentsJson,
            )
            "launch_app" -> AppStrings.get(R.string.trace_arg_launch_app, "Open app")
            "present_to_user" -> AppStrings.get(R.string.trace_arg_present_to_user, "Present to main screen")
            "get_current_context" -> AppStrings.get(R.string.trace_arg_get_current_context, "Read time and location")
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            "tap" -> summarizePointArguments(
                AppStrings.get(R.string.trace_arg_tap, "Tap screen"),
                toolCall.argumentsJson,
            )
            "long_press" -> summarizePointArguments(
                AppStrings.get(R.string.trace_arg_long_press, "Long-press screen"),
                toolCall.argumentsJson,
            )
            "tap_area" -> AppStrings.get(R.string.trace_arg_tap_area, "Tap area")
            "tap_element" -> summarizeElementArguments(
                AppStrings.get(R.string.trace_arg_tap_element, "Tap element"),
                toolCall.argumentsJson,
            )
            "long_press_element" -> summarizeElementArguments(
                AppStrings.get(R.string.trace_arg_long_press_element, "Long-press element"),
                toolCall.argumentsJson,
            )
            "swipe" -> AppStrings.get(R.string.trace_arg_swipe, "Swipe screen")
            "scroll" -> summarizeScrollArguments(
                AppStrings.get(R.string.trace_arg_scroll, "Scroll screen"),
                toolCall.argumentsJson,
            )
            "scroll_element" -> summarizeScrollArguments(
                AppStrings.get(R.string.trace_arg_scroll_element, "Scroll element"),
                toolCall.argumentsJson,
                withIndex = true,
            )
            "press_key" -> summarizePressKeyArguments(toolCall.argumentsJson)
            "wait" -> summarizeWaitArguments(toolCall.argumentsJson)
            "wait_for_text" -> AppStrings.get(R.string.trace_arg_wait_for_text, "Wait for text")
            "wait_for_package" -> AppStrings.get(R.string.trace_arg_wait_for_package, "Wait for app ready")
            "open_system_panel" -> AppStrings.get(R.string.trace_arg_open_system_panel, "Open system panel")
            "read_image" -> AppStrings.get(R.string.trace_arg_read_image, "View image")
            ImageGenToolCatalog.TOOL_NAME -> summarizeImageGenArguments(toolCall.argumentsJson)
            AgentSubagentToolCatalog.TOOL_NAME -> summarizeSubagentArguments(toolCall.argumentsJson)
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> summarizeSubagentProfileArguments(toolCall.argumentsJson)
            AgentConversationToolCatalog.READ_HISTORY ->
                AppStrings.get(R.string.trace_arg_read_history, "Read current session history")
            "memory_get", "character_memory_get", "memory_write", "character_memory_write" ->
                AgentPersonalResultFormatter.summarizeArguments(toolCall.name, toolCall.argumentsJson)
                    ?: AppStrings.get(R.string.trace_arg_memory_fallback, "Memory")
            "skills_list" -> AppStrings.get(R.string.trace_arg_skills_list, "View skill list")
            "skills_read" -> AppStrings.get(R.string.trace_arg_skills_read, "Read skill")
            "skills_read_resource" -> AppStrings.get(R.string.trace_arg_skills_read_resource, "Read skill resource")
            "skills_list_curated" -> AppStrings.get(R.string.trace_arg_skills_list_curated, "Browse curated skills")
            "skills_inspect_github" -> AppStrings.get(R.string.trace_arg_skills_inspect_github, "View skill details")
            "skills_install_from_github" -> AppStrings.get(R.string.trace_arg_skills_install_from_github, "Install skill")
            else -> {
                val label = DEVICE_ACTION_LABELS[toolCall.name]?.resolve()
                    ?: AgentPhoneToolCatalog.entries.firstOrNull { it.name == toolCall.name }?.title
                    ?: io.github.mangi.eta.agent.context.PersonalSearchTools.searches.firstOrNull { it.name == toolCall.name }?.title
                    ?: when (toolCall.name) {
                        "search_notes" -> AppStrings.get(R.string.trace_arg_search_notes, "Search notes")
                        "search_system_memories" ->
                            AppStrings.get(R.string.trace_arg_search_system_memories, "Search system memory")
                        "read_personal_item" ->
                            AppStrings.get(R.string.trace_arg_read_personal_item, "Read retrieval details")
                        "summarize_bills" ->
                            AppStrings.get(R.string.trace_arg_summarize_bills, "Summarize bills")
                        else -> null
                    }
                when {
                    label == null -> AppStrings.get(R.string.trace_arg_preparing, "Preparing")
                    toolCall.name.startsWith("search_") ->
                        summarizeQueryArguments(label, toolCall.argumentsJson)
                    else -> label
                }
            }
        }

    /** Commands enter the run trace as a redacted, user-visible projection; logs still record only the length. */
    fun displayCommand(toolCall: AgentModelClient.ToolCall): String? =
        if (toolCall.name == AgentSubagentToolCatalog.TOOL_NAME) {
            SubagentActivitySnapshot.initialFromArguments(toolCall.argumentsJson)?.encodeForWire()
        } else if (toolCall.name == ImageGenToolCatalog.TOOL_NAME) {
            GeneratedImageActivityPayload.initialFromArguments(toolCall.argumentsJson)?.encode()
        } else if (toolCall.name in AgentTerminalToolCatalog.names || toolCall.name == "run_command") {
            runCatching {
                JSONObject(toolCall.argumentsJson)
                    .optString("command")
                    .trim()
                    .takeIf { it.isNotBlank() && it.length <= MAX_DISPLAY_COMMAND_CHARS }
                    ?.redactDisplaySecrets()
            }.getOrNull()
        } else {
            null
        }

    private fun String.redactDisplaySecrets(): String {
        val redacted = AppStrings.get(R.string.trace_redacted, "<hidden>")
        return replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}=$redacted"
        }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}$redacted"
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$redacted"
            }
    }

    /** External URI summaries never record path, query, fragment, or user info. */
    private fun summarizeImageGenArguments(argumentsJson: String): String =
        runCatching {
            val args = JSONObject(argumentsJson)
            val refs = args.optJSONArray("reference_images")?.length() ?: 0
            val count = args.optInt("count", 1).coerceIn(1, 4)
            val action = if (refs > 0) {
                AppStrings.get(R.string.trace_arg_edit_image, "Edit image")
            } else {
                AppStrings.get(R.string.trace_arg_generate_image, "Generate image")
            }
            if (count > 1) {
                AppStrings.get(R.string.trace_arg_image_count, "%1\$s · %2\$d images", action, count)
            } else {
                action
            }
        }.getOrDefault(AppStrings.get(R.string.trace_arg_generate_image, "Generate image"))

    fun summarizeOpenUriArguments(argumentsJson: String): String {
        val label = AppStrings.get(R.string.trace_arg_open_uri, "Hand to external app")
        return runCatching {
            val raw = JSONObject(argumentsJson).optString("uri").trim()
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull(label, scheme, host).joinToString(" · ")
        }.getOrDefault(label)
    }

    /** browser_use summaries expose only the action and a safely extracted host. */
    fun summarizeBrowserArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").browserActionLabel()
            val host = safeHttpHost(arguments.optString("url"))
            listOfNotNull(action, host).joinToString(" · ")
        }.getOrElse { AppStrings.get(R.string.trace_arg_browser_action, "Browser action") }

    private fun summarizeFetchArguments(argumentsJson: String): String = runCatching {
        val arguments = JSONObject(argumentsJson)
        val label = if (arguments.has("document_id")) {
            AppStrings.get(R.string.trace_arg_fetch_continue, "Continue reading page")
        } else {
            AppStrings.get(R.string.trace_arg_fetch_url, "Read page")
        }
        listOfNotNull(label, safeHttpHost(arguments.optString("url"))?.removePrefix("www.")).joinToString(" · ")
    }.getOrDefault(AppStrings.get(R.string.trace_arg_fetch_url, "Read page"))

    private fun summarizeTerminalArguments(toolName: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = terminalActionLabel(toolName, arguments.optString("action", if (toolName == "terminal") "exec" else ""))
            val hasEnvironment = !arguments.has("session_id") && (
                toolName == "terminal" && arguments.optString("action", "exec") in setOf("open", "exec", "open_and_exec") ||
                    arguments.optString("action") in setOf("start", "daemon_start"))
            val environment = if (hasEnvironment) arguments.optString("environment", "android").terminalEnvironmentLabel() else null
            val identity = arguments.optString("identity")
                .takeIf { it == "root" || it == "user" }
            buildList {
                add(terminalLabel(toolName))
                add(action)
                environment?.let(::add)
                identity?.let(::add)
                if (arguments.optBoolean("async", false)) {
                    add(AppStrings.get(R.string.trace_arg_terminal_async, "Background"))
                }
            }.joinToString(" · ")
        }.getOrDefault(AppStrings.get(R.string.trace_terminal_default, "Terminal"))

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            val countText = AppStrings.quantity(
                R.plurals.trace_arg_char_count,
                chars,
                "%1\$d characters",
                "%1\$d characters",
                chars,
            )
            "$label · $countText"
        }.getOrDefault(label)

    /** Shows only the action and length, never echoing the body: the input may be a password or a private message. */
    private fun summarizeTypeText(argumentsJson: String): String =
        runCatching {
            val args = JSONObject(argumentsJson)
            val chars = args.optString("text").length
            val label = when {
                chars == 0 -> AppStrings.get(R.string.trace_arg_clear_text, "Clear text")
                args.optString("mode") == "append" -> AppStrings.get(R.string.trace_arg_append_text, "Append text")
                else -> AppStrings.get(R.string.trace_arg_input_text, "Enter text")
            }
            buildString {
                append(label)
                if (chars > 0) {
                    val countText = AppStrings.quantity(
                        R.plurals.trace_arg_char_count,
                        chars,
                        "%1\$d characters",
                        "%1\$d characters",
                        chars,
                    )
                    append(" · ").append(countText)
                }
                if (args.optBoolean("submit")) {
                    append(" · ").append(AppStrings.get(R.string.trace_arg_submit, "Submit"))
                }
            }
        }.getOrDefault(AppStrings.get(R.string.trace_arg_input_text, "Enter text"))

    /** Search keywords are queries the user initiated, shown directly; still single-lined and length-truncated. */
    private fun summarizeQueryArguments(label: String, argumentsJson: String): String =
        runCatching {
            val query = sanitizeSummaryValue(
                JSONObject(argumentsJson).optString("query"),
                MAX_QUERY_SUMMARY_CHARS,
            )
            if (query.isNotBlank()) "$label · $query" else label
        }.getOrDefault(label)

    private fun summarizePointArguments(label: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            "$label · (${arguments.optInt("x")}, ${arguments.optInt("y")})"
        }.getOrDefault(label)

    private fun summarizeElementArguments(label: String, argumentsJson: String): String =
        runCatching {
            val index = JSONObject(argumentsJson).optInt("index", -1)
            if (index >= 0) "$label · #$index" else label
        }.getOrDefault(label)

    private fun summarizeScrollArguments(
        label: String,
        argumentsJson: String,
        withIndex: Boolean = false,
    ): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            buildList {
                add(label)
                if (withIndex) {
                    arguments.optInt("index", -1).takeIf { it >= 0 }?.let { add("#$it") }
                }
                arguments.optString("direction").scrollDirectionLabel()?.let(::add)
            }.joinToString(" · ")
        }.getOrDefault(label)

    private fun summarizePressKeyArguments(argumentsJson: String): String {
        val label = AppStrings.get(R.string.trace_arg_press_key, "Key press")
        return runCatching {
            val button = JSONObject(argumentsJson).optString("button").pressKeyLabel()
            listOfNotNull(label, button).joinToString(" · ")
        }.getOrDefault(label)
    }

    private fun summarizeWaitArguments(argumentsJson: String): String {
        val waitLabel = AppStrings.get(R.string.trace_arg_wait, "Wait")
        return runCatching {
            val durationMs = JSONObject(argumentsJson).optInt("duration_ms", 1_000)
                .coerceAtLeast(0)
            val duration = if (durationMs >= 1_000) {
                val formatted = String.format(Locale.US, "%.1f", durationMs / 1_000f)
                    .trimEnd('0').trimEnd('.')
                AppStrings.get(R.string.trace_unit_seconds_text, "%1\$s seconds", formatted)
            } else {
                AppStrings.get(R.string.trace_unit_ms, "%1\$d ms", durationMs)
            }
            "$waitLabel · $duration"
        }.getOrDefault(waitLabel)
    }

    private fun summarizeObservationArguments(argumentsJson: String): String {
        val label = AppStrings.get(R.string.trace_arg_observe_screen, "Observe screen")
        return runCatching {
            val options = AgentScreenObservationContract.resolve(JSONObject(argumentsJson))
            buildList {
                add(label)
                if (options.includeScreenshot) {
                    add(AppStrings.get(R.string.trace_arg_with_screenshot, "with screenshot"))
                }
                if (options.includeUiTree) {
                    add(AppStrings.get(R.string.trace_arg_with_ui_tree, "with UI tree"))
                }
            }.joinToString(" · ")
        }.getOrDefault(label)
    }

    /** Outcome success/failure serves events and UI state, no longer relying on markers in the summary text. */
    fun isSuccessResult(result: AgentModelClient.ToolResult): Boolean =
        parseResultJson(result)?.optBoolean("ok", true) ?: true

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val json = parseResultJson(result)
        if (toolName == "web_search" || toolName == "fetch_url") return summarizeWebResult(toolName, json)
        // When the terminal exit_code != 0, ok=false but there is no code field, so it must take the dedicated branch to preserve the exit code and output
        if (toolName in AgentTerminalToolCatalog.names || toolName == "run_command") {
            return summarizeTerminalResult(toolName, json)
        }
        if (json?.optString("status") == "unconfirmed") {
            return AppStrings.get(R.string.trace_res_unconfirmed, "Action submitted, effect unconfirmed")
        }
        if (!isSuccessResult(result)) return summarizeFailure(json)
        if (json != null) {
            val summary = AgentDeviceResultFormatter.summarize(toolName, json)
                ?: AgentPhoneResultFormatter.summarize(toolName, json)
                ?: AgentPersonalResultFormatter.summarize(toolName, json)
                ?: AgentFileResultFormatter.summarize(toolName, json)
            if (summary != null) return summary
        }
        val done = AppStrings.get(R.string.trace_res_done, "Done")
        return when (toolName) {
            BROWSER_TOOL_NAME -> json?.let(::summarizeBrowserResult)
                ?: AppStrings.get(R.string.trace_res_browser_done, "Browser action done")
            ImageGenToolCatalog.TOOL_NAME -> {
                val count = json?.optInt("count", 0) ?: 0
                if (count > 0) {
                    AppStrings.quantity(
                        R.plurals.trace_res_images_generated,
                        count,
                        "Generated %1\$d images",
                        "Generated %1\$d images",
                        count,
                    )
                } else {
                    AppStrings.get(R.string.trace_res_image_gen_done, "Image generation done")
                }
            }
            AgentSubagentToolCatalog.TOOL_NAME -> json?.let(::summarizeSubagentResult) ?: done
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME -> json?.let(::summarizeSubagentProfileResult) ?: done
            AgentConversationToolCatalog.READ_HISTORY ->
                AppStrings.get(R.string.trace_res_history_read, "History page read")
            "observe_screen" -> if (result.images.isNotEmpty()) {
                AppStrings.get(R.string.trace_res_screen_captured, "Captured current screen")
            } else {
                AppStrings.get(R.string.trace_res_ui_captured, "Captured current UI")
            }
            "tap", "tap_element", "tap_area" -> AppStrings.get(R.string.trace_res_tapped, "Tapped")
            "long_press", "long_press_element" -> AppStrings.get(R.string.trace_res_long_pressed, "Long-pressed")
            "swipe" -> AppStrings.get(R.string.trace_res_swiped, "Swiped")
            "scroll", "scroll_element" -> AppStrings.get(R.string.trace_res_scrolled, "Scrolled")
            "type_text", "input_text", "paste_text" ->
                AppStrings.get(R.string.trace_res_text_entered, "Text entered")
            "clear_text" -> AppStrings.get(R.string.trace_res_text_cleared, "Text cleared")
            "press_key" -> AppStrings.get(R.string.trace_res_key_pressed, "Key pressed")
            "wait" -> AppStrings.get(R.string.trace_res_wait_done, "Wait completed")
            "wait_for_text" -> AppStrings.get(R.string.trace_res_text_appeared, "Target text appeared")
            "wait_for_package" -> AppStrings.get(R.string.trace_res_app_ready, "App ready")
            "inspect_app" -> AppStrings.get(R.string.trace_res_app_inspected, "App info retrieved")
            "get_clipboard" -> AppStrings.get(R.string.trace_res_clipboard_read, "Clipboard read")
            "set_clipboard" -> AppStrings.get(R.string.trace_res_clipboard_copied, "Copied to clipboard")
            "search_apps" -> json?.let(::summarizeSearchAppsResult) ?: done
            "launch_app" -> json?.let(::summarizeLaunchAppResult)
                ?: AppStrings.get(R.string.trace_res_opened, "Opened")
            "present_to_user" -> json?.let(::summarizePresentToUserResult)
                ?: AppStrings.get(R.string.trace_res_presented, "Presented to main screen")
            "skills_list", "skills_list_curated", "skills_inspect_github", "skills_install_from_github" -> json?.let {
                summarizeSkillResult(toolName, it) ?: summarizeGenericResult(it, result)
            } ?: done
            else -> json?.let { summarizeGenericResult(it, result) } ?: done
        }
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /**
     * Web error bodies, search terms, and page titles may echo private URLs, so keep only counts. Actions and hosts are already in the parameter summary,
     * so the result gives only the conclusion, avoiding repeating the same line for the working process.
     */
    private fun summarizeWebResult(toolName: String, json: JSONObject?): String {
        val done = AppStrings.get(R.string.trace_res_done, "Done")
        if (json == null) return done
        if (!json.optBoolean("ok", true)) {
            val failedLabel = if (toolName == "web_search") {
                AppStrings.get(R.string.trace_res_web_search_failed, "Web search failed")
            } else {
                AppStrings.get(R.string.trace_res_web_fetch_failed, "Web page read failed")
            }
            val code = json.optString("code").takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }
            return listOfNotNull(failedLabel, code?.let { "code=$it" }).joinToString(" · ")
        }
        if (toolName == "web_search") {
            val count = json.optJSONArray("results")?.length() ?: json.firstNonNegativeInt("count")
            return when (count) {
                null -> done
                0 -> AppStrings.get(R.string.trace_res_no_results, "No results found")
                else -> AppStrings.quantity(
                    R.plurals.trace_res_web_search_found,
                    count,
                    "Found %1\$d search results",
                    "Found %1\$d search results",
                    count,
                )
            }
        }
        val chars = (json.opt("text") as? String)?.length
            ?: json.firstNonNegativeInt("returned_chars", "text_chars")
            ?: return done
        val formatted = formatCharCount(chars)
        return if (json.optBoolean("has_more")) {
            AppStrings.get(R.string.trace_res_read_prefix, "Read first %1\$s", formatted)
        } else {
            AppStrings.get(R.string.trace_res_read_amount, "Read %1\$s", formatted)
        }
    }

    /** Failure summaries keep the code= marker so run logs can extract a stable error code; message is the reason given by the tool side. */
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add(AppStrings.get(R.string.trace_res_failed, "Failed"))
            reason?.let(::add)
            code?.let { add("code=$it") }
        }.joinToString(" · ")
    }

    private fun summarizeSubagentArguments(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson)
        val resumeId = sanitizeSummaryValue(args.optString("resume"), 32)
        val subagentType = sanitizeSummaryValue(args.optString("subagent_type"), 32)
            .lowercase()
            .ifBlank { if (resumeId.isNotBlank()) "subagent" else SubagentProfile.DEFAULT_TYPE }
        val description = sanitizeSummaryValue(args.optString("description"), MAX_QUERY_SUMMARY_CHARS)
        listOfNotNull(
            subagentType,
            description.takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { "Subagent" }
    }.getOrDefault("Subagent")

    private fun summarizeSubagentResult(json: JSONObject): String {
        val toolCalls = json.optInt("tool_calls", 0)
        return if (toolCalls > 0) {
            AppStrings.quantity(
                R.plurals.trace_res_subagent_tool_calls,
                toolCalls,
                "Done · %1\$d tool calls",
                "Done · %1\$d tool calls",
                toolCalls,
            )
        } else {
            AppStrings.get(R.string.trace_res_done, "Done")
        }
    }

    private fun summarizeSubagentProfileArguments(argumentsJson: String): String = runCatching {
        val args = JSONObject(argumentsJson)
        val action = args.optString("action").trim().lowercase()
        val name = sanitizeSummaryValue(args.optString("name"), 32)
        val label = when (action) {
            "save" -> AppStrings.get(R.string.trace_arg_subagent_save, "Save Subagent")
            "delete" -> AppStrings.get(R.string.trace_arg_subagent_delete, "Delete Subagent")
            "get" -> AppStrings.get(R.string.trace_arg_subagent_get, "Read Subagent")
            else -> AppStrings.get(R.string.trace_arg_subagent_list, "View Subagent list")
        }
        if (name.isNotBlank() && action != "list") "$label · $name" else label
    }.getOrDefault(AppStrings.get(R.string.trace_arg_subagent_manage, "Manage Subagent"))

    private fun summarizeSubagentProfileResult(json: JSONObject): String =
        when (json.optString("action")) {
            "save" -> listOfNotNull(
                AppStrings.get(R.string.trace_res_saved, "Saved"),
                json.optString("name").takeIf(String::isNotBlank),
            ).joinToString(" · ")
            "delete" -> listOfNotNull(
                AppStrings.get(R.string.trace_res_deleted, "Deleted"),
                json.optString("name").takeIf(String::isNotBlank),
            ).joinToString(" · ")
            "list" -> {
                val count = json.optJSONArray("profiles")?.length() ?: 0
                AppStrings.quantity(
                    R.plurals.trace_res_subagent_count,
                    count,
                    "%1\$d Subagents",
                    "%1\$d Subagents",
                    count,
                )
            }
            else -> AppStrings.get(R.string.trace_res_done, "Done")
        }

    private fun summarizeSkillResult(toolName: String, json: JSONObject): String? {
        if (toolName == "skills_install_from_github") {
            val installed = json.optJSONArray("installed") ?: return null
            val count = installed.length()
            val base = AppStrings.quantity(
                R.plurals.trace_res_skills_installed,
                count,
                "Installed %1\$d skills",
                "Installed %1\$d skills",
                count,
            )
            return if (json.optString("available") == "next_turn") {
                "$base · ${AppStrings.get(R.string.trace_res_available_next_turn, "Available next turn")}"
            } else {
                base
            }
        }
        val items = json.optJSONArray("items") ?: return null
        val total = items.length()
        val installed = (0 until total).count { items.optJSONObject(it)?.opt("installed") == true }
        val base = AppStrings.quantity(
            R.plurals.trace_res_skills_found,
            total,
            "Found %1\$d skills",
            "Found %1\$d skills",
            total,
        )
        return if (installed > 0) {
            val installedText = AppStrings.quantity(
                R.plurals.trace_res_skills_already_installed,
                installed,
                "%1\$d installed",
                "%1\$d installed",
                installed,
            )
            "$base · $installedText"
        } else {
            base
        }
    }

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String =
        buildList {
            if (json.has("verified") && !json.optBoolean("verified")) {
                add(AppStrings.get(R.string.trace_res_executed_unconfirmed, "Executed, effect unconfirmed"))
            }
            val items = json.optJSONArray("items") ?: json.optJSONArray("results")
            val matches = json.optJSONArray("matches")
            val entries = json.optJSONArray("entries")
            val apps = json.optJSONArray("apps")
            val candidates = json.optJSONArray("candidates")

            when {
                items != null -> {
                    val count = items.length()
                    if (count == 0) {
                        add(AppStrings.get(R.string.trace_res_no_results, "No results found"))
                    } else {
                        add(
                            AppStrings.quantity(
                                R.plurals.trace_res_found_results,
                                count,
                                "Found %1\$d results",
                                "Found %1\$d results",
                                count,
                            )
                        )
                    }
                }
                matches != null -> {
                    val count = matches.length()
                    if (count == 0) {
                        add(AppStrings.get(R.string.trace_res_no_matches, "No matches found"))
                    } else {
                        add(
                            AppStrings.quantity(
                                R.plurals.trace_res_found_matches,
                                count,
                                "Found %1\$d matches",
                                "Found %1\$d matches",
                                count,
                            )
                        )
                    }
                }
                entries != null -> {
                    val count = entries.length()
                    if (count == 0) {
                        add(AppStrings.get(R.string.trace_res_no_entries, "No content"))
                    } else {
                        add(
                            AppStrings.quantity(
                                R.plurals.trace_res_fetched_entries,
                                count,
                                "Fetched %1\$d items",
                                "Fetched %1\$d items",
                                count,
                            )
                        )
                    }
                }
                apps != null -> {
                    val count = apps.length()
                    if (count == 0) {
                        add(AppStrings.get(R.string.trace_res_no_matching_apps, "No matching apps"))
                    } else {
                        add(
                            AppStrings.quantity(
                                R.plurals.trace_res_found_apps,
                                count,
                                "Found %1\$d apps",
                                "Found %1\$d apps",
                                count,
                            )
                        )
                    }
                }
                candidates != null -> {
                    val count = candidates.length()
                    add(
                        AppStrings.quantity(
                            R.plurals.trace_res_candidates,
                            count,
                            "%1\$d candidates",
                            "%1\$d candidates",
                            count,
                        )
                    )
                }
                json.has("bytes_read") -> {
                    val bytes = json.optLong("bytes_read")
                    add(
                        AppStrings.quantity(
                            R.plurals.trace_res_bytes_read,
                            bytes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                            "Read %1\$d bytes",
                            "Read %1\$d bytes",
                            bytes,
                        )
                    )
                }
                json.has("replacements") -> {
                    val count = json.optInt("replacements")
                    add(
                        AppStrings.quantity(
                            R.plurals.trace_res_replacements,
                            count,
                            "Replaced %1\$d occurrences",
                            "Replaced %1\$d occurrences",
                            count,
                        )
                    )
                }
                result.images.isNotEmpty() -> {
                    val count = result.images.size
                    add(
                        AppStrings.quantity(
                            R.plurals.trace_res_image_count,
                            count,
                            "%1\$d images",
                            "%1\$d images",
                            count,
                        )
                    )
                }
                isEmpty() -> add(AppStrings.get(R.string.trace_res_done, "Done"))
            }
            if (json.optBoolean("truncated") || json.optBoolean("has_more")) {
                add(AppStrings.get(R.string.trace_res_has_more, "More available"))
            }
            if (json.optBoolean("partial") || json.optBoolean("scan_limited")) {
                add(AppStrings.get(R.string.trace_res_partial_scope, "Limited scan scope"))
            }
        }.joinToString(" · ")

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val noApps = AppStrings.get(R.string.trace_res_no_matching_apps, "No matching apps")
        val apps = json.optJSONArray("apps") ?: return noApps
        val total = apps.length()
        if (total == 0) return noApps
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        val header = AppStrings.quantity(
            R.plurals.trace_res_search_apps_found,
            total,
            "Found %1\$d apps",
            "Found %1\$d apps",
            total,
        )
        val shown = names.take(MAX_LISTED_APP_NAMES)
        if (shown.isEmpty()) return header
        val separator = AppStrings.get(R.string.trace_list_separator, ", ")
        val joined = shown.joinToString(separator)
        val namesText = if (total > shown.size) {
            AppStrings.get(R.string.trace_list_with_more, "%1\$s, etc.", joined)
        } else {
            joined
        }
        return "$header · $namesText"
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        val virtual = json.optString("canvas") == "virtual"
        val action = if (json.optString("launch_status") == "unconfirmed") {
            AppStrings.get(R.string.trace_res_launch_unconfirmed, "Open requested, awaiting confirmation")
        } else {
            AppStrings.get(R.string.trace_res_opened, "Opened")
        }
        val base = if (appName.isNotBlank()) "$action · $appName" else action
        return if (virtual) {
            "$base · ${AppStrings.get(R.string.trace_res_virtual_display, "Virtual display")}"
        } else {
            base
        }
    }

    private fun summarizePresentToUserResult(json: JSONObject): String {
        val label = AppStrings.get(R.string.trace_res_presented, "Presented to main screen")
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        return if (appName.isNotBlank()) "$label · $appName" else label
    }

    /**
     * Terminal results show the user the exit status and an output preview; the output may be very long,
     * so keep only the first few lines and append an ellipsis marker when truncated.
     */
    private fun summarizeTerminalResult(toolName: String, json: JSONObject?): String {
        if (json == null) return terminalLabel(toolName)
        if (json.optString("code").isNotBlank()) return summarizeFailure(json)
        if (!json.has("exit_code") || json.isNull("exit_code")) {
            val action = terminalActionLabel(toolName, json.optString("action"))
            return if (json.optBoolean("ok", true)) {
                "${terminalLabel(toolName)} · $action"
            } else {
                "${AppStrings.get(R.string.trace_res_failed, "Failed")} · $action"
            }
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> AppStrings.get(R.string.trace_res_terminal_timeout, "Failed · timed out")
            exitCode == 0 -> AppStrings.get(R.string.trace_res_terminal_done, "Execution done")
            else -> AppStrings.get(R.string.trace_res_terminal_exit_code, "Failed · exit code %1\$d", exitCode)
        }
        val output = if (exitCode == 0) {
            json.optString("stdout", json.optString("output"))
        } else {
            json.optString("stderr").ifBlank { json.optString("stdout", json.optString("output")) }
        }
        val truncated = json.optBoolean("stdout_truncated", false) ||
            json.optBoolean("stderr_truncated", false)
        val preview = terminalOutputPreview(output, truncated) ?: return status
        return "$status\n$preview"
    }

    private fun terminalOutputPreview(output: String, truncated: Boolean): String? {
        val normalized = output.trim()
        if (normalized.isEmpty()) return null
        val allLines = normalized.lines()
        var preview = allLines.take(MAX_TERMINAL_PREVIEW_LINES).joinToString("\n")
        var capped = allLines.size > MAX_TERMINAL_PREVIEW_LINES || truncated
        if (preview.length > MAX_TERMINAL_PREVIEW_CHARS) {
            preview = preview.take(MAX_TERMINAL_PREVIEW_CHARS)
            capped = true
        }
        return if (capped) "$preview\n…" else preview
    }

    private fun summarizeBrowserResult(json: JSONObject): String {
        val page = json.optJSONObject("page")
            ?: json.optJSONObject("page_info")
            ?: json.optJSONObject("pageInfo")
        val action = json.optString("action")
            .takeIf { it in BROWSER_ACTIONS }
            ?: "unknown"
        val host = sequenceOf(json, page)
            .filterNotNull()
            .flatMap { source ->
                sequenceOf("url", "current_url", "currentUrl", "final_url", "finalUrl")
                    .map(source::optString)
            }
            .mapNotNull(::safeHttpHost)
            .firstOrNull()
        val title = sequenceOf(json, page)
            .filterNotNull()
            .map { it.opt("title") }
            .filterIsInstance<String>()
            .map(::sanitizeSummaryValue)
            .firstOrNull { it.isNotBlank() }
        val textChars = sequenceOf(json, page)
            .filterNotNull()
            .mapNotNull { source ->
                source.firstNonNegativeInt("text_length", "textLength", "text_chars", "textChars")
            }
            .firstOrNull()
            ?: sequenceOf(json, page)
                .filterNotNull()
                .flatMap { source -> sequenceOf("text", "readable", "content").map(source::opt) }
                .filterIsInstance<String>()
                .map(String::length)
                .firstOrNull()
        val elementCount = json.firstNonNegativeInt("element_count", "elementCount", "elements_count")
            ?: json.optJSONArray("elements")?.length()

        return buildList {
            add(action.browserSuccessLabel())
            host?.let(::add)
            title?.let { add(AppStrings.get(R.string.trace_browser_title, "\"%1\$s\"", it)) }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let {
                    add(AppStrings.get(R.string.trace_browser_approx_chars, "About %1\$s", formatCharCount(it)))
                }
            }
            elementCount?.let {
                add(
                    AppStrings.quantity(
                        R.plurals.trace_browser_elements,
                        it,
                        "%1\$d elements",
                        "%1\$d elements",
                        it,
                    )
                )
            }
            if (json.optBoolean("truncated", false)) {
                add(AppStrings.get(R.string.trace_res_truncated, "Truncated"))
            }
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String {
        if (chars >= 10_000) {
            val unit = AppStrings.get(R.string.trace_char_count_large_unit, "k")
            return if (unit == "k") {
                val thousands = String.format(Locale.US, "%.1f", chars / 1_000f).trimEnd('0').trimEnd('.')
                AppStrings.get(R.string.trace_char_count_thousands, "%1\$sk chars", thousands)
            } else {
                val tenThousands = String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.')
                AppStrings.get(R.string.trace_char_count_ten_thousands, "%1\$sk chars", tenThousands)
            }
        }
        return AppStrings.quantity(R.plurals.trace_char_count, chars, "%1\$d chars", "%1\$d chars", chars)
    }

    private fun JSONObject.firstNonNegativeInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key)) return@firstNotNullOfOrNull null
            optInt(key, -1).takeIf { it >= 0 }
        }

    private fun sanitizeSummaryValue(value: String, maxChars: Int = 80): String =
        value.replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }

    private fun safeHttpHost(rawUrl: String): String? =
        rawUrl.trim()
            .takeIf(String::isNotEmpty)
            ?.let { value ->
                runCatching {
                    val uri = URI(value)
                    uri.host
                        ?.takeIf {
                            uri.scheme.equals("http", ignoreCase = true) ||
                                uri.scheme.equals("https", ignoreCase = true)
                        }
                        ?.lowercase()
                        ?.take(160)
                }.getOrNull()
            }

    private fun String.browserActionLabel(): String = when (this) {
        "navigate" -> AppStrings.get(R.string.trace_browser_act_navigate, "Open page")
        "get_readable" -> AppStrings.get(R.string.trace_browser_act_get_readable, "Extract text")
        "get_text" -> AppStrings.get(R.string.trace_browser_act_get_text, "Read text")
        "find_elements" -> AppStrings.get(R.string.trace_browser_act_find_elements, "Find elements")
        "click" -> AppStrings.get(R.string.trace_browser_act_click, "Click page")
        "type" -> AppStrings.get(R.string.trace_browser_act_type, "Enter content")
        "scroll" -> AppStrings.get(R.string.trace_browser_act_scroll, "Scroll page")
        "screenshot" -> AppStrings.get(R.string.trace_browser_act_screenshot, "Page screenshot")
        "get_page_info" -> AppStrings.get(R.string.trace_browser_act_get_page_info, "View page info")
        "go_back" -> AppStrings.get(R.string.trace_browser_act_go_back, "Page back")
        "go_forward" -> AppStrings.get(R.string.trace_browser_act_go_forward, "Page forward")
        "reload" -> AppStrings.get(R.string.trace_browser_act_reload, "Reload page")
        "wait_for_selector" -> AppStrings.get(R.string.trace_browser_act_wait_for_selector, "Wait for page element")
        else -> AppStrings.get(R.string.trace_arg_browser_action, "Browser action")
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> AppStrings.get(R.string.trace_browser_res_navigate, "Opened")
        "get_readable" -> AppStrings.get(R.string.trace_browser_res_get_readable, "Text extracted")
        "get_text" -> AppStrings.get(R.string.trace_browser_res_get_text, "Text read")
        "find_elements" -> AppStrings.get(R.string.trace_browser_res_find_elements, "Elements found")
        "click" -> AppStrings.get(R.string.trace_browser_res_click, "Page clicked")
        "type" -> AppStrings.get(R.string.trace_browser_res_type, "Content entered")
        "scroll" -> AppStrings.get(R.string.trace_browser_res_scroll, "Page scrolled")
        "screenshot" -> AppStrings.get(R.string.trace_browser_res_screenshot, "Screenshot taken")
        "get_page_info" -> AppStrings.get(R.string.trace_browser_res_get_page_info, "Page info read")
        "go_back" -> AppStrings.get(R.string.trace_browser_res_go_back, "Went back")
        "go_forward" -> AppStrings.get(R.string.trace_browser_res_go_forward, "Went forward")
        "reload" -> AppStrings.get(R.string.trace_browser_res_reload, "Reloaded")
        "wait_for_selector" -> AppStrings.get(R.string.trace_browser_res_wait_for_selector, "Target element found")
        else -> AppStrings.get(R.string.trace_res_browser_done, "Browser action done")
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> AppStrings.get(R.string.trace_dir_up, "Up")
        "down" -> AppStrings.get(R.string.trace_dir_down, "Down")
        "left" -> AppStrings.get(R.string.trace_dir_left, "Left")
        "right" -> AppStrings.get(R.string.trace_dir_right, "Right")
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> AppStrings.get(R.string.trace_key_back, "Back")
        "home" -> AppStrings.get(R.string.trace_key_home, "Home")
        "recents", "recent" -> AppStrings.get(R.string.trace_key_recents, "Recents")
        "notifications" -> AppStrings.get(R.string.trace_key_notifications, "Notifications")
        "quick_settings" -> AppStrings.get(R.string.trace_key_quick_settings, "Quick settings")
        "power" -> AppStrings.get(R.string.trace_key_power, "Power")
        "volume_up" -> AppStrings.get(R.string.trace_key_volume_up, "Volume up")
        "volume_down" -> AppStrings.get(R.string.trace_key_volume_down, "Volume down")
        "mute" -> AppStrings.get(R.string.trace_key_mute, "Mute")
        else -> null
    }

    private fun terminalLabel(toolName: String): String = when (toolName) {
        "terminal_job" -> AppStrings.get(R.string.trace_terminal_job, "Async command")
        "terminal_daemon" -> AppStrings.get(R.string.trace_terminal_daemon, "Background service")
        else -> AppStrings.get(R.string.trace_terminal_default, "Terminal")
    }

    private fun terminalActionLabel(toolName: String, action: String): String = when (toolName) {
        "terminal_job" -> when (action) {
            "start" -> AppStrings.get(R.string.trace_terminal_job_start, "Start command")
            "list" -> AppStrings.get(R.string.trace_terminal_job_list, "Task list")
            "read" -> AppStrings.get(R.string.trace_terminal_job_read, "Read output")
            "wait" -> AppStrings.get(R.string.trace_terminal_job_wait, "Wait completed")
            "stop" -> AppStrings.get(R.string.trace_terminal_job_stop, "Stop command")
            else -> AppStrings.get(R.string.trace_terminal_job_other, "Task operation")
        }
        "terminal_daemon" -> when (action) {
            "start" -> AppStrings.get(R.string.trace_terminal_daemon_start, "Start service")
            "list" -> AppStrings.get(R.string.trace_terminal_daemon_list, "Service list")
            "logs" -> AppStrings.get(R.string.trace_terminal_daemon_logs, "View logs")
            "stop" -> AppStrings.get(R.string.trace_terminal_daemon_stop, "Stop service")
            else -> AppStrings.get(R.string.trace_terminal_daemon_other, "Service operation")
        }
        else -> action.terminalActionLabel()
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "check_root" -> AppStrings.get(R.string.trace_terminal_act_check_root, "Check Root authorization")
        "open" -> AppStrings.get(R.string.trace_terminal_act_open, "Create session")
        "exec" -> AppStrings.get(R.string.trace_terminal_act_exec, "Execute command")
        "open_and_exec" -> AppStrings.get(R.string.trace_terminal_act_open_and_exec, "Single execution")
        "read_async_result" -> AppStrings.get(R.string.trace_terminal_act_read_async, "Read background output")
        "close" -> AppStrings.get(R.string.trace_terminal_act_close, "Close terminal")
        "daemon_start" -> AppStrings.get(R.string.trace_terminal_act_daemon_start, "Start daemon task")
        "daemon_list" -> AppStrings.get(R.string.trace_terminal_act_daemon_list, "Daemon task list")
        "daemon_logs" -> AppStrings.get(R.string.trace_terminal_act_daemon_logs, "View daemon logs")
        "daemon_stop" -> AppStrings.get(R.string.trace_terminal_act_daemon_stop, "Stop daemon task")
        else -> AppStrings.get(R.string.trace_terminal_act_other, "Terminal operation")
    }

    private fun String.terminalEnvironmentLabel(): String = when (this) {
        "linux" -> "Linux"
        "alpine" -> "Alpine"
        "debian" -> "Debian"
        else -> "Android"
    }

    private data class LocalizedLabel(
        @StringRes val resId: Int,
        val fallback: String,
    ) {
        fun resolve(): String = AppStrings.get(resId, fallback)
    }

    private companion object {
        const val BROWSER_TOOL_NAME = "browser_use"
        const val MAX_DISPLAY_COMMAND_CHARS = 4_000
        const val MAX_QUERY_SUMMARY_CHARS = 30
        const val MAX_LISTED_APP_NAMES = 3
        const val MAX_TERMINAL_PREVIEW_LINES = 10
        const val MAX_TERMINAL_PREVIEW_CHARS = 800
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)\b([A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|SECRET)[A-Z0-9_]*)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_FLAG = Regex(
            """(?i)(--?(?:password|passwd|token|api[-_]?key|secret)(?:\s*=\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_HEADER = Regex(
            """(?i)\b(Authorization|Proxy-Authorization|X-Api-Key)(\s*:\s*)[^'"\r\n;&|]+"""
        )
        val BROWSER_ACTIONS = setOf(
            "navigate",
            "get_readable",
            "get_text",
            "find_elements",
            "click",
            "type",
            "scroll",
            "screenshot",
            "get_page_info",
            "go_back",
            "go_forward",
            "reload",
            "wait_for_selector",
        )
        val BROWSER_TEXT_ACTIONS = setOf("get_readable", "get_text")

        /** Structured device tools show only the action label, exposing no parameters. */
        val DEVICE_ACTION_LABELS = mapOf(
            "set_alarm" to LocalizedLabel(R.string.trace_act_set_alarm, "Set alarm"),
            "set_timer" to LocalizedLabel(R.string.trace_act_set_timer, "Set timer"),
            "device_status" to LocalizedLabel(R.string.trace_act_device_status, "View device status"),
            "network_info" to LocalizedLabel(R.string.trace_act_network_info, "View network info"),
            "top_memory_apps" to LocalizedLabel(R.string.trace_act_top_memory_apps, "View memory usage ranking"),
            "top_storage_apps" to LocalizedLabel(R.string.trace_act_top_storage_apps, "View storage usage ranking"),
            "media_control" to LocalizedLabel(R.string.trace_act_media_control, "Control media playback"),
            "set_volume" to LocalizedLabel(R.string.trace_act_set_volume, "Adjust volume"),
            "get_setting" to LocalizedLabel(R.string.trace_act_get_setting, "Read system settings"),
            "wifi_credentials" to LocalizedLabel(R.string.trace_act_wifi_credentials, "Read Wi-Fi password"),
            "recent_notifications" to LocalizedLabel(R.string.trace_act_recent_notifications, "Read recent notifications"),
            "search_notification_history" to LocalizedLabel(R.string.trace_act_search_notification_history, "Search notification history"),
            "recent_app_activity" to LocalizedLabel(R.string.trace_act_recent_app_activity, "View app activity"),
            "app_usage_summary" to LocalizedLabel(R.string.trace_act_app_usage_summary, "View app usage stats"),
            "get_current_location" to LocalizedLabel(R.string.trace_act_get_current_location, "Get current location"),
            "get_device_environment" to LocalizedLabel(R.string.trace_act_get_device_environment, "View device environment"),
            "list_alarms" to LocalizedLabel(R.string.trace_act_list_alarms, "View alarm list"),
            "list_active_timers" to LocalizedLabel(R.string.trace_act_list_active_timers, "View timers"),
            "search_clipboard_history" to LocalizedLabel(R.string.trace_act_search_clipboard_history, "Search clipboard history"),
            "get_health_summary" to LocalizedLabel(R.string.trace_act_get_health_summary, "View health summary"),
            "read_sms_code" to LocalizedLabel(R.string.trace_act_read_sms_code, "Read SMS verification code"),
            "get_logcat" to LocalizedLabel(R.string.trace_act_get_logcat, "Read system logs"),
            "search_media" to LocalizedLabel(R.string.trace_act_search_media, "Search media files"),
            "search_audio" to LocalizedLabel(R.string.trace_act_search_audio, "Search audio"),
            "search_recordings" to LocalizedLabel(R.string.trace_act_search_recordings, "Search recordings"),
            "search_files" to LocalizedLabel(R.string.trace_act_search_files, "Search files"),
            "search_calendar_events" to LocalizedLabel(R.string.trace_act_search_calendar_events, "Search calendar events"),
            "search_contacts" to LocalizedLabel(R.string.trace_act_search_contacts, "Search contacts"),
            "search_call_history" to LocalizedLabel(R.string.trace_act_search_call_history, "Search call history"),
            "search_messages" to LocalizedLabel(R.string.trace_act_search_messages, "Search messages"),
            "search_downloads" to LocalizedLabel(R.string.trace_act_search_downloads, "Search downloads"),
            "search_coloros_notes" to LocalizedLabel(R.string.trace_act_search_coloros_notes, "Search notes"),
            "search_coloros_recordings" to LocalizedLabel(R.string.trace_act_search_coloros_recordings, "Search voice recorder"),
            "search_recording_summaries" to LocalizedLabel(R.string.trace_act_search_recording_summaries, "Search recording summaries"),
            "search_coloros_memories" to LocalizedLabel(R.string.trace_act_search_coloros_memories, "Search Breeno memories"),
            "search_saved_places" to LocalizedLabel(R.string.trace_act_search_saved_places, "Search saved places"),
            "search_personal_orders" to LocalizedLabel(R.string.trace_act_search_personal_orders, "Search personal orders"),
            "search_qq_chat_images" to LocalizedLabel(R.string.trace_act_search_qq_chat_images, "Search QQ chat images"),
            "search_wechat_chat_images" to LocalizedLabel(R.string.trace_act_search_wechat_chat_images, "Search WeChat chat images"),
            "set_setting" to LocalizedLabel(R.string.trace_act_set_setting, "Modify system settings"),
            "set_device_state" to LocalizedLabel(R.string.trace_act_set_device_state, "Modify device state"),
            "app_state_control" to LocalizedLabel(R.string.trace_act_app_state_control, "Manage app state"),
        )
    }
}
