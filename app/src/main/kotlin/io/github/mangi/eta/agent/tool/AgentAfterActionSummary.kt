package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.device.RootShellDeviceController.ElementObservation
import io.github.mangi.eta.agent.device.RootShellDeviceController.Observation
import io.github.mangi.eta.agent.device.RootShellDeviceController.UiNode
import io.github.mangi.eta.agent.device.ScreenObservationMetadata
import org.json.JSONArray
import org.json.JSONObject

/**
 * The model projection of a post-action observation: first the conclusion (whether the UI changed,
 * and whether the app or window switched), then the new node list.
 * Change detection compares only the visible semantics of nodes (text, description, class name,
 * view_id, position), never timestamps or animation frames.
 */
internal object AgentAfterActionSummary {
    fun build(before: ElementObservation?, observation: Observation): JSONObject {
        val metadata = JSONObject(observation.content)
        val after = observation.elementObservation
        val nodes = after?.nodes.orEmpty()
        val status = metadata.optString("ui_tree_status").ifBlank {
            when {
                after == null -> "query_failed"
                nodes.isEmpty() -> "empty"
                else -> "available"
            }
        }
        val changed = if (status == "available" && nodes.isNotEmpty()) {
            before == null || signature(before.nodes) != signature(nodes)
        } else {
            null
        }
        val note = if (changed == null) {
            metadata.optString("ui_tree_note").ifBlank {
                ScreenObservationMetadata.uiTreeNote(status, observation.image != null)
            }
        } else if (changed) {
            "This is the new UI after the action; you can continue using this observation_id, and call observe_screen again when more nodes or a screenshot are needed"
        } else {
            "The UI shows no visible change, so the action may not have taken effect; try a different target or approach instead of repeating it unchanged"
        }
        return JSONObject()
            .put("observation_id", after?.id ?: JSONObject.NULL)
            .put("observation_source", after?.source?.wireName ?: metadata.opt("observation_source") ?: JSONObject.NULL)
            .put("package", after?.packageName ?: metadata.optJSONObject("focus")?.optString("package").orEmpty())
            .put("screen_changed", changed ?: JSONObject.NULL)
            .put(
                "package_changed",
                if (after != null) before != null && before.packageName != after.packageName else JSONObject.NULL,
            )
            .put(
                "window_changed",
                if (after != null) before != null && before.windowId != after.windowId else JSONObject.NULL,
            )
            .put("ui_tree_status", status)
            .put("ui_tree_truncated", after?.truncated ?: false)
            .put("ui_nodes", JSONArray().also { array -> nodes.forEach { array.put(it.compactJson()) } })
            .put("note", note)
            .apply {
                metadata.optJSONObject("focus")?.let { put("focus", it) }
                metadata.optString("code").takeIf(String::isNotBlank)?.let { put("code", it) }
            }
    }

    private fun signature(nodes: List<UiNode>): List<String> =
        nodes.map { "${it.className}|${it.viewId}|${it.text}|${it.desc}|${it.bounds.toShortString()}" }

    /** Compact fields: omit booleans whose default is false and the package name to reduce the token cost of the observation attached to each step. */
    private fun UiNode.compactJson(): JSONObject = JSONObject()
        .put("index", index)
        .apply {
            if (text.isNotBlank()) put("text", text)
            if (desc.isNotBlank()) put("desc", desc)
            put("class", className.substringAfterLast('.'))
            if (viewId.isNotBlank()) put("view_id", viewId.substringAfterLast('/'))
            put("center", JSONObject().put("x", centerX).put("y", centerY))
            if (clickable) put("clickable", true)
            if (longClickable) put("long_clickable", true)
            if (scrollable) put("scrollable", true)
            if (editable) put("editable", true)
            if (focused) put("focused", true)
            if (password) put("password", true)
            if (!enabled) put("enabled", false)
            checked?.let { put("checked", it) }
            if (selected) put("selected", true)
            if (hint.isNotBlank()) put("hint", hint)
        }
}
