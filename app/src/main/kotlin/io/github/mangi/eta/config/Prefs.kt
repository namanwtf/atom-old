package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray

/**
 * Central hub for module configuration.
 *
 * - Hook processes (system_server / SystemUI / Google / screen-recognition and system-memory, etc.) call at module load
 *   [attachRemote] to cache the framework-provided read-only [SharedPreferences]; all interception callbacks then use [isEnabled]
 *   to read the remote preferences held by the current process.
 * - Switches consumed by Eta Runtime itself are kept in the app's private config and do not depend on Xposed Service.
 * - Switches consumed by hooks are written to RemotePreferences via [remotePreferencesForUi];
 *   when XposedService is not ready, no local fake fallback is provided.
 *
 * Based on [io.github.libxposed.api.XposedInterface.getRemotePreferences] from libxposed API 102
 * and [XposedService.getRemotePreferences] from service 102; both ends share the same group.
 */
internal object Prefs {

    /** Remote config group name; UI writes and Hook reads must agree. */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** Keys for all feature switches. Defaults are defined independently per feature risk. */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // Compatible with the legacy boolean protocol; the new UI no longer writes it, and when the tri-state config is absent, true still means Gemini.
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_DEFAULT_REASONING_EFFORT = "agent_default_reasoning_effort"
        const val AGENT_AUTO_COMPACTION_ENABLED = "agent_auto_compaction_enabled"
        const val AGENT_SUBAGENT_ENABLED = "agent_subagent_enabled"
        const val AGENT_VIRTUAL_DISPLAY_ENABLED = "agent_virtual_display_enabled"
        const val AGENT_KEEP_SCREEN_ON_ENABLED = "agent_keep_screen_on_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val AGENT_DISABLED_TOOLS_JSON = "agent_disabled_tools_json"

        /** All boolean switches and their defaults. */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPACTION_ENABLED to true,
            AGENT_SUBAGENT_ENABLED to true,
            AGENT_VIRTUAL_DISPLAY_ENABLED to true,
            AGENT_KEEP_SCREEN_ON_ENABLED to true,
        )

        /** Switches that Eta Runtime ultimately decides and that do not require the Xposed framework to be online. */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPACTION_ENABLED,
            AGENT_SUBAGENT_ENABLED,
            AGENT_VIRTUAL_DISPLAY_ENABLED,
            AGENT_KEEP_SCREEN_ON_ENABLED,
        )
    }

    /** Read-only remote preferences cached by the hook process, injected by ModuleMain in onModuleLoaded. */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** Called by the app process: initializes the Agent config that does not depend on Xposed Service. */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    ).also(::migrateLegacyGroupSwitchesIfNeeded)
                }
            }
        }
    }

    /** Called by the hook process: caches the framework-provided read-only SharedPreferences. */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** The hook process listens for config changes pushed by the framework; the caller must strongly reference the listener for the process lifetime. */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * Reads a boolean switch. When remote is unavailable (framework not injected or call failed), falls back to each feature's own default;
     * defaults stay consistent with the settings page.
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }

    fun getString(key: String): String {
        return remote?.getString(key, "") ?: ""
    }

    fun defaultReasoningEffort(
        preferences: SharedPreferences? = localAgent ?: remote,
    ): ReasoningEffort {
        val raw = runCatching {
            preferences?.getString(Keys.AGENT_DEFAULT_REASONING_EFFORT, null)
        }.getOrNull()
        val parsed = ReasoningEffort.fromWireValue(raw)
        return if (parsed != null && parsed != ReasoningEffort.OFF) parsed else ReasoningEffort.DEFAULT
    }

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    @Volatile
    private var cachedRemoteService: XposedService? = null

    @Volatile
    private var cachedRemotePrefs: SharedPreferences? = null

    /**
     * Reads the bound service's RemotePreferences only from the in-process cache, avoiding a synchronous Binder call on the main thread during the UI's first frame.
     */
    fun cachedRemotePreferencesForUi(service: XposedService?): SharedPreferences? {
        if (service == null) return null
        return cachedRemotePrefs?.takeIf { cachedRemoteService === service }
    }

    /**
     * The UI process obtains a writable RemotePreferences.
     *
     * The commit of [XposedService.getRemotePreferences] synchronously waits for the binder to be committed to LSPosed
     * database and returns false on failure; returns null when the service is not ready, keeping the UI non-writable.
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? {
        if (service == null) {
            cachedRemoteService = null
            cachedRemotePrefs = null
            return null
        }
        cachedRemotePrefs?.takeIf { cachedRemoteService === service }?.let { return it }
        return runCatching { service.getRemotePreferences(GROUP) }
            .getOrNull()
            ?.also { resolved ->
                cachedRemoteService = service
                cachedRemotePrefs = resolved
            }
    }

    /** Local Agent config used by the Eta settings page and Runtime, independent of LSPosed. */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    /**
     * On first upgrade, migrate existing RemotePreferences values into local first; afterwards the local value is the source of truth, and it is
     * written back to the remote when the framework is available, so hook entries still assembling requests in target processes get a consistent initial config.
     */
    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        val localEditor = local.edit()
        val remoteEditor = remotePreferences.edit()
        var updateLocal = false
        var updateRemote = false

        Keys.LOCAL_AGENT_KEYS.forEach { key ->
            val default = Keys.BOOLEAN_DEFAULTS.getValue(key)
            when {
                local.contains(key) -> {
                    remoteEditor.putBoolean(key, local.getBoolean(key, default))
                    updateRemote = true
                }
                remotePreferences.contains(key) -> {
                    localEditor.putBoolean(key, remotePreferences.getBoolean(key, default))
                    updateLocal = true
                }
            }
        }
        when {
            local.contains(Keys.AGENT_DISABLED_TOOLS_JSON) -> {
                remoteEditor.putString(
                    Keys.AGENT_DISABLED_TOOLS_JSON,
                    local.getString(Keys.AGENT_DISABLED_TOOLS_JSON, "[]"),
                )
                updateRemote = true
            }
            remotePreferences.contains(Keys.AGENT_DISABLED_TOOLS_JSON) -> {
                localEditor.putString(
                    Keys.AGENT_DISABLED_TOOLS_JSON,
                    remotePreferences.getString(Keys.AGENT_DISABLED_TOOLS_JSON, "[]"),
                )
                updateLocal = true
            }
        }
        if (updateLocal) localEditor.commit()
        if (updateRemote) runCatching { remoteEditor.commit() }
    }

    /** Maps a runtime tool name to the primary switch id in the tool list, ensuring different action variants of the same capability are governed by the same switch. */
    fun canonicalToolSwitchId(toolName: String): String = when (toolName) {
        "tap" -> "tap_area"
        "long_press_element" -> "long_press"
        "scroll_element" -> "scroll"
        "input_text", "replace_text", "clear_text", "paste_text" -> "type_text"
        "wait", "wait_for_package" -> "wait_for_text"
        "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
        "search_coloros_notes" -> "search_notes"
        "search_coloros_memories" -> "search_system_memories"
        else -> PersonalSearchTools.canonical(toolName)
    }

    fun disabledTools(preferences: SharedPreferences? = localAgent ?: remote): Set<String> {
        val raw = runCatching {
            preferences?.getString(Keys.AGENT_DISABLED_TOOLS_JSON, null)
        }.getOrNull().orEmpty()
        if (raw.isBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.optString(index).trim()
                    if (item.isNotEmpty()) add(item)
                }
            }
        }.getOrDefault(emptySet())
    }

    fun isToolEnabledInSet(toolName: String, disabled: Set<String>): Boolean {
        if (disabled.isEmpty()) return true
        val canonical = canonicalToolSwitchId(toolName)
        return toolName !in disabled && canonical !in disabled
    }

    fun isToolEnabled(
        toolName: String,
        preferences: SharedPreferences? = localAgent ?: remote,
    ): Boolean = isToolEnabledInSet(toolName, disabledTools(preferences))

    fun setToolEnabled(
        toolName: String,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean = setToolsEnabled(listOf(toolName), enabled, service)

    fun setToolsEnabled(
        toolNames: Collection<String>,
        enabled: Boolean,
        service: XposedService? = null,
    ): Boolean {
        val local = localAgent ?: return false
        val updated = disabledTools(local).toMutableSet()
        toolNames.forEach { rawName ->
            val canonical = canonicalToolSwitchId(rawName)
            if (enabled) {
                updated -= rawName
                updated -= canonical
            } else {
                updated += canonical
            }
        }
        val encoded = JSONArray(updated.sorted()).toString()
        val editor = local.edit().putString(Keys.AGENT_DISABLED_TOOLS_JSON, encoded)
        LEGACY_TOOL_GROUP_KEYS.forEach { key ->
            if (local.contains(key) && !local.getBoolean(key, true)) {
                editor.putBoolean(key, true)
            }
        }
        val committed = runCatching { editor.commit() }.getOrDefault(false)
        if (committed && service != null) {
            reconcileAgentPreferences(service)
        }
        return committed
    }

    private fun migrateLegacyGroupSwitchesIfNeeded(local: SharedPreferences) {
        if (local.contains(Keys.AGENT_DISABLED_TOOLS_JSON)) return
        val disabledLegacyGroups = LEGACY_TOOL_GROUP_KEYS.filter { key ->
            local.contains(key) && !local.getBoolean(key, true)
        }
        if (disabledLegacyGroups.isEmpty()) return
        val migratedDisabled = linkedSetOf<String>()
        if (Keys.AGENT_BROWSER_TOOLS in disabledLegacyGroups) {
            migratedDisabled += listOf("web_search", "fetch_url", "browser_use")
        }
        if (Keys.AGENT_TERMINAL_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentTerminalToolCatalog.names
            migratedDisabled += AgentFileToolCatalog.names
            migratedDisabled += "read_image"
        }
        if (Keys.AGENT_DEVICE_DIRECT_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentPhoneToolCatalog.direct
            migratedDisabled += listOf(
                "set_alarm", "set_timer", "device_status", "inspect_app",
                "network_info", "top_memory_apps", "top_storage_apps",
                "media_control", "set_volume",
            )
        }
        if (Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS in disabledLegacyGroups) {
            migratedDisabled += PersonalSearchTools.names
            migratedDisabled += AgentPhoneToolCatalog.reads
            migratedDisabled += listOf(
                "read_personal_item", "summarize_bills", "get_setting", "wifi_credentials",
                "recent_notifications", "search_notification_history", "recent_app_activity",
                "app_usage_summary", "get_current_location", "get_device_environment",
                "list_alarms", "list_active_timers", "search_clipboard_history",
                "get_health_summary", "read_sms_code", "get_logcat", "search_media",
                "search_audio", "search_recordings", "search_files", "search_calendar_events",
                "search_contacts", "search_call_history", "search_messages", "search_downloads",
                "search_notes", "search_coloros_recordings", "search_recording_summaries",
                "search_system_memories", "search_saved_places", "search_personal_orders",
                "search_qq_chat_images", "search_wechat_chat_images",
            )
        }
        if (Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS in disabledLegacyGroups) {
            migratedDisabled += AgentPhoneToolCatalog.writes
            migratedDisabled += listOf("set_setting", "set_device_state", "app_state_control")
        }
        val editor = local.edit()
            .putString(Keys.AGENT_DISABLED_TOOLS_JSON, JSONArray(migratedDisabled.sorted()).toString())
        disabledLegacyGroups.forEach { key -> editor.putBoolean(key, true) }
        runCatching { editor.commit() }
    }

    private val LEGACY_TOOL_GROUP_KEYS = listOf(
        Keys.AGENT_TERMINAL_TOOLS,
        Keys.AGENT_BROWSER_TOOLS,
        Keys.AGENT_DEVICE_DIRECT_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
        Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
    )
}
