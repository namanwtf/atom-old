package io.github.namanwtf.atom.agent.tool

import org.json.JSONObject

/** The command result and the state read-back are reported separately; an unknown result must not be disguised as a change. */
internal object DeviceMutationResult {
    fun observed(
        tool: String,
        userId: Int,
        scope: String,
        before: Any?,
        after: Any?,
        expected: Any,
        commandAccepted: Boolean,
        failureCode: String? = null,
        matchesExpected: Boolean = after == expected,
    ): JSONObject {
        val verified = commandAccepted && after != null && matchesExpected
        val changed: Any = if (before != null && after != null) before != after else JSONObject.NULL
        return JSONObject()
            .put("ok", verified).put("tool", tool).put("user_id", userId).put("scope", scope)
            .put("before", before ?: JSONObject.NULL).put("after", after ?: JSONObject.NULL)
            .put("expected", expected).put("command_accepted", commandAccepted)
            .put("changed", changed).put("verified", verified)
            .put("status", if (verified) "verified" else if (commandAccepted) "unconfirmed" else "failed")
            .also { result ->
                if (!verified) {
                    result.put("code", failureCode ?: "STATE_CHANGE_UNCONFIRMED")
                    result.put("message", if (commandAccepted) "The operation request was submitted but the target state is unconfirmed; check the state first and do not blindly repeat the action" else "The system interface did not complete the operation")
                }
            }
    }
}
