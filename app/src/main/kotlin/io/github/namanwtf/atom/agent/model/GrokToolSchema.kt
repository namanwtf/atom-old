package io.github.namanwtf.atom.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** The subscription interface compiles object branches of the root union independently; only the required branches that depend on outer fields are expanded. */
internal object GrokToolSchema {
    fun project(parameters: JSONObject): JSONObject {
        val result = JSONObject(parameters.toString())
        if (result.optString("type") != "object" || hasReferences(result)) return result
        val union = listOf("oneOf", "anyOf").singleOrNull(result::has) ?: return result
        val branches = result.optJSONArray(union) ?: return result
        if (branches.length() == 0) return result
        val requirements = (0 until branches.length()).map { index ->
            val branch = branches.optJSONObject(index) ?: return result
            if (branch.length() != 1) return result
            val required = branch.optJSONArray("required") ?: return result
            if ((0 until required.length()).any { required.opt(it) !is String }) return result
            required
        }
        val common = JSONObject(result.toString()).apply { remove(union) }
        val commonRequired = common.optJSONArray("required") ?: JSONArray()
        result.put(union, JSONArray().also { expanded ->
            for (required in requirements) {
                val names = linkedSetOf<String>()
                for (array in listOf(commonRequired, required)) {
                    for (index in 0 until array.length()) names += array.getString(index)
                }
                expanded.put(JSONObject(common.toString()).put("required", JSONArray(names.toList())))
            }
        })
        return result
    }

    // Copying references or anchors may change the resolution scope; keep them as-is for the server to interpret.
    private fun hasReferences(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { key ->
            key.startsWith("$") || key == "id" || hasReferences(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).any { hasReferences(value.opt(it)) }
        else -> false
    }
}
