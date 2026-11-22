package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolCallHistoryTest {
    @Test
    fun repeatedBatchesKeepEveryResultPairedWithoutChangingSource() {
        val source = JSONArray()
            .put(message("system", "rule"))
            .put(assistant("tool_call_0"))
            .put(result("tool_call_0", "first-round result"))
            .put(message("user", "continue"))
            .put(assistant("tool_call_0", "unique"))
            .put(result("unique", "second-round other result"))
            .put(result("tool_call_0", "second-round result"))
            .put(assistant("tool_call_0"))
            .put(result("tool_call_0", "third-round result"))
        val original = source.toString()

        val projected = OpenAiRequestMessages.forChatCompletions(source)
        val ids = callIds(projected)

        assertEquals(4, ids.size)
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("tool_call_0", ids[0])
        assertEquals("unique", ids[2])
        assertEquals(ids[0], resultId(projected, "first-round result"))
        assertEquals(ids[1], resultId(projected, "second-round result"))
        assertEquals(ids[2], resultId(projected, "second-round other result"))
        assertEquals(ids[3], resultId(projected, "third-round result"))
        assertEquals(source.length(), projected.length())
        assertEquals("execute the tool first", projected.getJSONObject(4).getString("content"))
        assertEquals("{\"fixture\":true}", projected.getJSONObject(4)
            .getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals(original, source.toString())
        assertEquals(projected.toString(), OpenAiRequestMessages.forChatCompletions(source).toString())

        ChatToolCallHistory.repairCompleteBatches(projected)
        assertEquals(ids, callIds(projected))
    }

    @Test
    fun distinctIdsAndUnrelatedMessageFieldsStayUnchanged() {
        val source = JSONArray()
            .put(message("user", "request"))
            .put(assistant("first").put("reasoning_content", "thinking"))
            .put(result("first", "result"))
            .put(assistant("second"))
            .put(result("second", "another result"))

        assertEquals(source.toString(), OpenAiRequestMessages.forChatCompletions(source).toString())
    }

    @Test
    fun replacementsAvoidLaterCallIdsAndOrphanResultIds() {
        val source = JSONArray()
            .put(assistant("duplicate"))
            .put(result("duplicate", "first-round result"))
            .put(assistant("duplicate"))
            .put(result("duplicate", "result to be fixed"))
            .put(assistant("call_atom_history_2_0"))
            .put(result("call_atom_history_2_0", "subsequent result"))
            .put(message("user", "boundary"))
            .put(result("call_atom_history_2_0_1", "orphan result"))
        val reserved = ChatToolCallHistory.referencedIds(source)

        val projected = OpenAiRequestMessages.forChatCompletions(source)
        val replacement = resultId(projected, "result to be fixed")

        assertFalse(replacement in reserved)
        assertEquals(callIds(projected)[1], replacement)
        assertEquals("call_atom_history_2_0", resultId(projected, "subsequent result"))
        assertEquals("call_atom_history_2_0_1", resultId(projected, "orphan result"))
        assertEquals(projected.toString(), OpenAiRequestMessages.forChatCompletions(source).toString())
    }

    @Test
    fun resultsCannotCrossAnyMessageBoundaryEvenWhenSystemMessagesAreMerged() {
        for (role in listOf("user", "system", "assistant", "developer")) {
            val source = JSONArray()
                .put(assistant("duplicate"))
                .put(result("duplicate", "complete result"))
                .put(assistant("duplicate"))
                .put(message(role, "boundary"))
                .put(result("duplicate", "cross-boundary result"))
            val original = source.toString()

            val projected = OpenAiRequestMessages.forChatCompletions(source)

            assertEquals(role, listOf("duplicate", "duplicate"), callIds(projected))
            assertEquals(role, "duplicate", resultId(projected, "cross-boundary result"))
            assertEquals(role, original, source.toString())
        }
    }

    @Test
    fun nonObjectMessagesKeepTheirPairingBoundaryAndOriginalIndicesDuringRepair() {
        val source = JSONArray()
            .put(assistant("duplicate"))
            .put(result("duplicate", "complete result"))
            .put(assistant("duplicate"))
            .put(JSONArray().put("invalid-message"))
            .put(result("duplicate", "cross-boundary result"))
            .put(assistant("duplicate"))
            .put(result("duplicate", "subsequent complete result"))
        val original = source.toString()

        val projected = OpenAiRequestMessages.forChatCompletions(source)

        assertEquals(listOf("duplicate", "duplicate", "call_atom_history_5_0"), callIds(projected))
        assertEquals("duplicate", resultId(projected, "cross-boundary result"))
        assertEquals("call_atom_history_5_0", resultId(projected, "subsequent complete result"))
        assertEquals(original, source.toString())
    }

    @Test
    fun malformedAndAmbiguousBatchesStayUntouched() {
        val invalidBatches = listOf(
            JSONArray().put(assistant("duplicate", "duplicate"))
                .put(result("duplicate", "result one")).put(result("duplicate", "result two")),
            JSONArray().put(assistant("duplicate", "missing"))
                .put(result("duplicate", "missing one result")),
            JSONArray().put(assistant("duplicate")),
            JSONArray().put(assistant("duplicate")).put(result("duplicate", "result"))
                .put(result("extra", "extra result")),
            JSONArray().put(assistant("duplicate")).put(result("duplicate", "result"))
                .put(result("duplicate", "duplicate result")),
            JSONArray().put(assistant("duplicate")).put(result("other", "non-matching result")),
            JSONArray().put(assistant("duplicate")).put(result("", "empty result ID")),
            JSONArray().put(assistant("duplicate")).put(result(" ", "blank result ID")),
            JSONArray().put(assistant("duplicate")).put(message("tool", "numeric result ID").put("tool_call_id", 1)),
            JSONArray().put(assistant("duplicate")).put(message("tool", "missing result ID")),
            JSONArray().put(assistant("duplicate")).put("invalid-message").put(result("duplicate", "cross-boundary result")),
            JSONArray().put(message("assistant", "no call")).put(result("duplicate", "orphan result")),
            JSONArray().put(message("assistant", "malformed call list").put("tool_calls", "invalid"))
                .put(result("duplicate", "orphan result")),
            JSONArray().put(message("assistant", "empty call list").put("tool_calls", JSONArray()))
                .put(result("duplicate", "orphan result")),
        ) + listOf("", " ", 1, JSONObject.NULL).map { invalidId ->
            JSONArray().put(message("assistant", "malformed ID").put("tool_calls", JSONArray()
                .put(call("duplicate")).put(call("unused").put("id", invalidId))))
                .put(result("duplicate", "result"))
        } + listOf(
            JSONArray().put(message("assistant", "missing ID").put("tool_calls", JSONArray()
                .put(call("duplicate")).put(JSONObject().put("type", "function"))))
                .put(result("duplicate", "result")),
            JSONArray().put(message("assistant", "malformed call").put("tool_calls", JSONArray()
                .put(call("duplicate")).put("invalid-call")))
                .put(result("duplicate", "result")),
        )

        invalidBatches.forEachIndexed { index, invalid ->
            val messages = JSONArray().put(assistant("duplicate")).put(result("duplicate", "previous result"))
            for (itemIndex in 0 until invalid.length()) messages.put(invalid.get(itemIndex))
            val before = messages.toString()

            ChatToolCallHistory.repairCompleteBatches(messages)

            assertEquals("invalid batch $index", before, messages.toString())
        }
    }

    @Test
    fun incompleteEarlierBatchRemainsUntouchedWhileLaterCompletePairGetsDistinctId() {
        val source = JSONArray()
            .put(assistant("duplicate"))
            .put(message("user", "new request"))
            .put(assistant("duplicate"))
            .put(result("duplicate", "fully paired result"))

        val projected = OpenAiRequestMessages.forChatCompletions(source)
        val ids = callIds(projected)

        assertEquals("duplicate", ids[0])
        assertNotEquals(ids[0], ids[1])
        assertEquals(ids[1], resultId(projected, "fully paired result"))
        assertEquals(source.length(), projected.length())
    }

    @Test
    fun referencedIdsOnlyIncludesNonBlankStringsInCallAndResultRoles() {
        val source = JSONArray()
            .put(assistant("real", "", " "))
            .put(message("assistant", "malformed call").put("tool_calls", JSONArray()
                .put(JSONObject().put("id", 123)).put(JSONObject().put("id", JSONObject.NULL)).put("invalid")))
            .put(result("orphan", "result"))
            .put(result("", "empty ID"))
            .put(message("tool", "non-string ID").put("tool_call_id", true))
            .put(message("user", "irrelevant field").put("tool_call_id", "ignored")
                .put("tool_calls", JSONArray().put(call("also_ignored"))))

        assertEquals(setOf("real", "orphan"), ChatToolCallHistory.referencedIds(source))
    }

    @Test
    fun responsesInstructionsDoNotRepairOrModifyMessages() {
        val source = JSONArray()
            .put(message("system", "rule"))
            .put(assistant("duplicate"))
            .put(result("duplicate", "result one"))
            .put(assistant("duplicate"))
            .put(result("duplicate", "result two"))
        val before = source.toString()

        assertEquals("rule", OpenAiRequestMessages.responsesInstructions(source))
        assertEquals(before, source.toString())
        assertTrue(callIds(source).all { it == "duplicate" })
    }

    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun assistant(vararg ids: String): JSONObject =
        message("assistant", "execute the tool first").put("tool_calls", JSONArray().also { calls ->
            ids.forEach { calls.put(call(it)) }
        })

    private fun call(id: String): JSONObject = JSONObject()
        .put("id", id)
        .put("type", "function")
        .put("function", JSONObject().put("name", "fixture_tool").put("arguments", "{\"fixture\":true}"))

    private fun result(id: String, content: String): JSONObject =
        message("tool", content).put("tool_call_id", id)

    private fun callIds(messages: JSONArray): List<String> = buildList {
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "assistant") continue
            val calls = message.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until calls.length()) add(calls.getJSONObject(callIndex).getString("id"))
        }
    }

    private fun resultId(messages: JSONArray, content: String): String =
        (0 until messages.length()).map { messages.getJSONObject(it) }
            .single { it.optString("role") == "tool" && it.optString("content") == content }
            .getString("tool_call_id")
}
