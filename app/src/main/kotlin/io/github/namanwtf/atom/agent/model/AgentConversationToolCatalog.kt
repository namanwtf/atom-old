package io.github.namanwtf.atom.agent.model

import org.json.JSONObject

/** Declares history retrieval for the current conversation only, never allowing the model to specify another conversation's identity. */
internal object AgentConversationToolCatalog {
    const val READ_HISTORY = "conversation_history"

    fun schema(): JSONObject = AgentToolSchema.function(
        name = READ_HISTORY,
        description = "Retrieve or expand the full sanitized history of the current session (including early messages that were folded away by context compaction)." +
            "When query is provided, search by keyword and return matched snippets with message_index, offset, and total_chars;" +
            "when query is omitted, expand the specified message's original text or read in paged order by message_index and offset. Continue along next_message_index and next_offset," +
            "subsequent reads stay on the same history, which can be refreshed by starting from zero; do not mistake a paginated fragment for the complete history. Sensitive tool raw text and ephemeral images are not in the persistent history.",
        parameters = JSONObject().put("type", "object").put("properties", JSONObject()
            .put("query", JSONObject().put("type", "string").put("maxLength", 500))
            .put("message_index", JSONObject().put("type", "integer").put("minimum", 0))
            .put("offset", JSONObject().put("type", "integer").put("minimum", 0))
            .put("max_chars", JSONObject().put("type", "integer").put("minimum", 256).put("maximum", 8000))),
    )
}
