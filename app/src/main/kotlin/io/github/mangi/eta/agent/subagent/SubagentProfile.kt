package io.github.mangi.eta.agent.subagent

import androidx.annotation.StringRes
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.core.AppStrings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Subagent role definition and tool whitelist (covers built-in profiles and custom profiles defined by users in `agents/<name>.md`).
 *
 * All Subagents are stripped of `subagent` / `subagent_profile` themselves (no recursive dispatch or self-modification)
 * as well as the exclusive physical-screen GUI and interactive browser tools, ensuring concurrent Subagents don't interfere with each other.
 */
internal data class SubagentProfile(
    val name: String,
    val summary: String,
    val whenToUse: String,
    val toolHighlights: List<String>,
    val roleInstruction: String,
    private val allowWriteFilesAndTerminal: Boolean = false,
    val builtin: Boolean = true,
    val override: Boolean = false,
    val explicitTools: Set<String>? = null,
    val disallowedTools: Set<String> = emptySet(),
    val filePath: String? = null,
    @StringRes val summaryRes: Int? = null,
) {
    fun localizedSummary(): String =
        summaryRes?.let { AppStrings.get(it, summary) } ?: summary

    fun buildSystemPrompt(): String =
        "$SUBAGENT_ROLE_PREFIX\n\n$roleInstruction"

    fun filterTools(allTools: JSONArray): JSONArray = JSONArray().also { filtered ->
        for (index in 0 until allTools.length()) {
            val item = allTools.optJSONObject(index) ?: continue
            val toolName = item.optJSONObject("function")?.optString("name").orEmpty()
            if (isToolAllowed(toolName)) {
                filtered.put(JSONObject(item.toString()))
            }
        }
    }

    fun isToolAllowed(toolName: String): Boolean {
        if (toolName.isBlank() || toolName in FORBIDDEN_TOOLS || toolName in disallowedTools) {
            return false
        }
        val customAllowlist = explicitTools
        if (customAllowlist != null) {
            if (toolName in customAllowlist) return true
            if ("personal_data" in customAllowlist &&
                (toolName in PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.reads)
            ) {
                return true
            }
            if ("*" in customAllowlist) {
                return toolName !in DEFAULT_DEVICE_WRITE_TOOLS
            }
            return false
        }
        if (toolName in READ_ONLY_TOOLS || toolName in PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.reads) {
            return true
        }
        if (!allowWriteFilesAndTerminal) return false
        return toolName in CODER_EXTRA_TOOLS || toolName.startsWith("mcp_")
    }

    companion object {
        const val TYPE_EXPLORE = "explore"
        const val TYPE_CODER = "coder"
        const val TYPE_PLAN = "plan"
        const val DEFAULT_TYPE = TYPE_EXPLORE

        private const val SUBAGENT_ROLE_PREFIX =
            "You are running as a subagent. The user messages you receive are dispatched by the main agent, not entered directly by the end user." +
                "The main agent cannot see your intermediate thinking or tool-call process, only the final message you produce at the end." +
                "Do not ask the end user questions directly; if the task is ambiguous or lacks prerequisites, clearly explain this to the main agent in your final message."

        val FORBIDDEN_TOOLS = setOf(
            AgentSubagentToolCatalog.TOOL_NAME,
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME,
            "observe_screen",
            "release_gui",
            "present_to_user",
            "tap",
            "tap_area",
            "tap_element",
            "long_press",
            "long_press_element",
            "swipe",
            "scroll",
            "scroll_element",
            "type_text",
            "input_text",
            "replace_text",
            "clear_text",
            "paste_text",
            "set_clipboard",
            "press_key",
            "open_system_panel",
            "wait",
            "wait_for_text",
            "wait_for_package",
            "launch_app",
            "open_uri",
            "browser_use",
            "memory_write",
            "character_memory_write",
            "skills_install_from_github",
            "terminal_daemon",
        )

        val READ_ONLY_TOOLS = setOf(
            "read_file",
            "stat_file",
            "list_directory",
            "glob_files",
            "grep_files",
            "read_image",
            "web_search",
            "fetch_url",
            "get_current_context",
            "search_apps",
            "inspect_app",
            "device_status",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "get_setting",
            "get_logcat",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "get_health_summary",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "search_clipboard_history",
            "get_clipboard",
            "get_flashlight",
            "get_device_state",
            "get_display_state",
            "get_sound_state",
            "get_hotspot",
            "read_personal_item",
            "summarize_bills",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_system_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
            "memory_get",
            "character_memory_get",
            "skills_list",
            "skills_read",
            "skills_read_resource",
        )

        /**
         * Device-state modification and action-type write tools. When not explicitly named, the `*` wildcard excludes these tools by default,
         * preventing concurrent Subagents from mutating each other's system settings, volume, or app state; they may still be explicitly
         * allowed by name in a custom profile's `tools` list (except `FORBIDDEN_TOOLS`).
         */
        val DEFAULT_DEVICE_WRITE_TOOLS: Set<String> = AgentPhoneToolCatalog.writes + setOf(
            "set_setting",
            "set_device_state",
            "app_state_control",
            "set_alarm",
            "set_timer",
            "set_volume",
            "media_control",
        )

        private val CODER_EXTRA_TOOLS =
            (AgentFileToolCatalog.names + AgentTerminalToolCatalog.names) - FORBIDDEN_TOOLS

        val EXPLORE = SubagentProfile(
            name = TYPE_EXPLORE,
            summary = "read-only exploration and multi-source information retrieval",
            whenToUse = "Fast read-only agent specialized for exploring files, personal device data (calendar, notes, messages, notifications, bills, media, system memories), logs, and web pages. Specify desired thoroughness in the prompt: \"quick\", \"medium\", or \"thorough\".",
            toolHighlights = listOf("read_file", "glob_files", "grep_files", "web_search", "fetch_url", "personal_data", "get_logcat"),
            roleInstruction = "You are a read-only exploration subagent (explore). Your job is to quickly and accurately retrieve files, device personal data, system state, or web content," +
                "You must never attempt to modify any file or device state. In your final message, give structured evidence, key paths/timestamps, and a summary of conclusions directly.",
            allowWriteFilesAndTerminal = false,
            builtin = true,
            summaryRes = R.string.subagent_i18n_explore_summary,
        )

        val CODER = SubagentProfile(
            name = TYPE_CODER,
            summary = "engineering execution, file editing, and terminal tasks",
            whenToUse = "General engineering and terminal agent with file-editing and shell execution tools; use it for delegated tasks that run commands, analyze APKs, edit files, or execute scripts.",
            toolHighlights = listOf("terminal", "read_file", "write_file", "edit_file", "glob_files", "grep_files", "web_search"),
            roleInstruction = "You are an engineering and terminal execution subagent (coder). When doing code development, Git, Python/Node scripts, text processing, or APK analysis, explicitly use environment=\"linux\" for terminal and file tools; when operating Android system commands or device state, use environment=\"android\"; the workspace and shared directories map to the same physical folder on both sides, so choose the environment and corresponding path by the nature of the operation and do not copy files back and forth. Your last message is the complete handoff report to the main agent:" +
                "clearly state which commands you ran or which files you modified, key output and verification results, and anything unfinished or requiring follow-up.",
            allowWriteFilesAndTerminal = true,
            builtin = true,
            summaryRes = R.string.subagent_i18n_coder_summary,
        )

        val PLAN = SubagentProfile(
            name = TYPE_PLAN,
            summary = "read-only architecture analysis and step-by-step plan design",
            whenToUse = "Read-only planning agent. Use when you need a step-by-step implementation or execution plan, key file/data identification, and trade-off analysis before making changes.",
            toolHighlights = listOf("read_file", "glob_files", "grep_files", "web_search", "fetch_url", "skills_read"),
            roleInstruction = "You are a read-only planning subagent (plan). You have read-only retrieval abilities and must not modify files or perform destructive operations." +
                "In your final message, clearly list: 1. confirmed context facts; 2. key questions still to verify; 3. a step-by-step execution plan with risk trade-offs.",
            allowWriteFilesAndTerminal = false,
            builtin = true,
            summaryRes = R.string.subagent_i18n_plan_summary,
        )

        val BUILTIN_PROFILES: List<SubagentProfile> = listOf(EXPLORE, CODER, PLAN)
        val BUILTIN_NAMES: Set<String> = BUILTIN_PROFILES.mapTo(linkedSetOf()) { it.name }

        fun resolve(
            name: String?,
            catalog: List<SubagentProfile> = SubagentProfileCatalog.listAll(),
            customRole: String? = null,
            customTools: List<String>? = null,
        ): SubagentProfile {
            val normalized = name?.trim()?.lowercase()
                ?.replace(Regex("[^a-z0-9_-]+"), "-")
                ?.trim('-')
                .orEmpty()
            val matched = if (normalized.isEmpty()) {
                catalog.firstOrNull { it.name == DEFAULT_TYPE } ?: EXPLORE
            } else {
                catalog.firstOrNull { it.name == normalized }
            }
            val cleanRole = customRole?.trim().orEmpty()
            val cleanTools = customTools
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it !in FORBIDDEN_TOOLS }
                ?.distinct()
                .orEmpty()

            if (matched != null) {
                // For existing presets (especially read-only presets like explore / plan), extra tools can only narrow the allowed set, never escalate it.
                val narrowedTools = cleanTools.filter { tool ->
                    when (tool) {
                        "*" -> false
                        "personal_data" -> PersonalSearchTools.names.any(matched::isToolAllowed) ||
                            AgentPhoneToolCatalog.reads.any(matched::isToolAllowed)
                        else -> matched.isToolAllowed(tool)
                    }
                }
                if (cleanRole.isEmpty() && narrowedTools.isEmpty()) return matched
                return matched.copy(
                    roleInstruction = if (cleanRole.isNotEmpty()) {
                        "${matched.roleInstruction}\n\nAdditional role requirements: $cleanRole"
                    } else {
                        matched.roleInstruction
                    },
                    explicitTools = if (narrowedTools.isNotEmpty()) narrowedTools.toSet() else matched.explicitTools,
                    toolHighlights = if (narrowedTools.isNotEmpty()) narrowedTools.take(8) else matched.toolHighlights,
                )
            }

            // When no preset matches, create a temporary, dedicated Subagent on the spot for this session only, without persisting it.
            val ephemeralName = normalized.ifEmpty { "general" }
            val instruction = cleanRole.ifEmpty {
                "You are a subagent ($ephemeralName) dedicated to the current subtask. Use available tools efficiently to gather evidence or complete the operation," +
                    "and return a structured, complete summary of conclusions to the main agent in your final message."
            }
            return SubagentProfile(
                name = ephemeralName,
                summary = cleanRole.ifEmpty { "temporary dedicated subagent" },
                whenToUse = cleanRole,
                toolHighlights = if (cleanTools.isNotEmpty()) {
                    cleanTools.take(8)
                } else {
                    listOf("read_file", "glob_files", "grep_files", "web_search", "personal_data", "terminal")
                },
                roleInstruction = instruction,
                allowWriteFilesAndTerminal = true,
                builtin = false,
                explicitTools = if (cleanTools.isNotEmpty()) cleanTools.toSet() else setOf("*"),
                summaryRes = if (cleanRole.isEmpty()) R.string.subagent_i18n_ephemeral_summary else null,
            )
        }
    }
}
