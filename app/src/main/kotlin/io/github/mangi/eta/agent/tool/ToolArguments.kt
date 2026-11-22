package io.github.mangi.eta.agent.tool

import org.json.JSONException
import org.json.JSONObject

// Android's getString converts JSON null, objects, and arrays into text; write operations must preserve the true text semantics.
internal fun JSONObject.requiredText(name: String): String =
    opt(name) as? String ?: throw JSONException("$name is missing or is not text")
