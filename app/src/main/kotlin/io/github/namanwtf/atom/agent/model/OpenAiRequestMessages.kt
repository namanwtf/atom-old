package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Projects Atom session messages into the system-instruction structure required by OpenAI-compatible requests. */
internal object OpenAiRequestMessages {
    fun forChatCompletions(source: JSONArray): JSONArray {
        val system = collectInstructions(source, SYSTEM_ROLES)
        val copied = JSONArray().also { messages ->
            for (index in 0 until source.length()) {
                val item = source.opt(index)
                messages.put(if (item is JSONObject) JSONObject(item.toString()) else item)
            }
        }
        // Preserve the original boundaries before merging system, so tool results spanning messages are not mis-paired into a complete batch.
        ChatToolCallHistory.repairCompleteBatches(copied)
        return JSONArray().also { messages ->
            if (system.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            for (index in 0 until copied.length()) {
                val message = copied.optJSONObject(index) ?: continue
                if (message.optString("role") !in SYSTEM_ROLES) messages.put(message.apply {
                    remove("_atom_context_summary")
                    remove("_atom_compacted_users")
                    remove("_atom_summary_through_user")
                    remove("_atom_compaction_archive_id")
                    remove("_atom_compaction_prompt")
                    remove("_atom_observation")
                    remove("_atom_steering")
                    remove("_atom_message_id")
                    remove("_atom_character_profile")
                    remove("_atom_assistant_screen_context")
                })
            }
        }
    }

    fun responsesInstructions(source: JSONArray): String =
        collectInstructions(source, RESPONSES_INSTRUCTION_ROLES)

    private fun collectInstructions(source: JSONArray, roles: Set<String>): String =
        buildList {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in roles) continue
                providerMessageText(message.opt("content"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }.joinToString("\n\n")

    private val SYSTEM_ROLES = setOf("system")
    private val RESPONSES_INSTRUCTION_ROLES = setOf("system", "developer")
}
