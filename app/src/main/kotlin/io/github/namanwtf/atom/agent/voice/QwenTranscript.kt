package io.github.namanwtf.atom.agent.voice

import org.json.JSONObject

/** text/stash is the replacement snapshot of the same sentence; a completed item must not be overwritten by a late preview. */
internal class QwenTranscript {
    private val items = linkedMapOf<String, String>()
    private val completed = mutableSetOf<String>()

    fun accept(event: JSONObject): String {
        val id = event.optString("item_id")
        when (event.optString("type")) {
            "conversation.item.input_audio_transcription.text" -> if (id !in completed) {
                items[id] = event.optString("text") + event.optString("stash")
            }
            "conversation.item.input_audio_transcription.completed" -> {
                items[id] = event.optString("transcript")
                completed += id
            }
        }
        return items.values.joinToString("")
    }

    fun result(): String = items.filterKeys { it in completed }.values.joinToString("").trim()
}
