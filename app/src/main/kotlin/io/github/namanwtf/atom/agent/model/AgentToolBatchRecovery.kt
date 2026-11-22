package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Unknown results in the interrupted record are marked explicitly, so recovery never replays already-executed tools or submits dangling tool calls. */
internal object AgentToolBatchRecovery {
    fun completeInterrupted(messages: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage> {
        val open = linkedSetOf<String>()
        val recovered = mutableListOf<AgentModelClient.ConversationMessage>()
        fun closeBatch() {
            open.forEach { id ->
                recovered += AgentModelClient.ConversationMessage(role = "tool", toolCallId = id,
                    content = JSONObject().put("ok", false).put("code", "TOOL_INTERRUPTED")
                        .put("message", "The tool batch was interrupted and the full result of this call was not obtained; execution state is unknown. Verify first and do not auto-replay.").toString())
            }
            open.clear()
        }
        messages.forEach { message ->
            if (message.role != "tool") closeBatch()
            recovered += message
            if (message.toolCallsJson.isNotBlank()) {
                val calls = JSONArray(message.toolCallsJson)
                for (index in 0 until calls.length()) open += calls.getJSONObject(index).getString("id")
            }
            if (message.role == "tool") open.remove(message.toolCallId)
        }
        closeBatch()
        return recovered
    }
}
