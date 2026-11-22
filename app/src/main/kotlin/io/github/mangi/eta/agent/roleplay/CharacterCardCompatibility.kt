package io.github.mangi.eta.agent.roleplay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object CharacterCardCompatibility {
    private val html = Regex(
        "<\\s*/?\\s*(?:script|iframe|style|div|span|details|summary|html|body|button|input|img|audio|video|canvas|table|p|br|a)(?=[\\s/>])",
        RegexOption.IGNORE_CASE,
    )

    fun warnings(card: CharacterCard): List<String> = buildList {
        val fields = listOf(card.description, card.personality, card.scenario, card.firstMessage,
            card.exampleMessages, card.systemPrompt, card.postHistoryInstructions) + card.alternateGreetings +
            ((card.extensions["depth_prompt"] as? JsonObject)?.text("prompt") ?: "") +
            (card.characterBook?.get("entries") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("content") }
        if (fields.any { CharacterMacros.hasUnsupportedMacros(it, card) }) {
            add("contains unsupported macros; the original text is preserved and no variable, condition, or script operations run.")
        }
        if (fields.any { html.containsMatchIn(it) }) {
            add("contains HTML or script UI markup; kept as text and no interactive UI runs.")
        }
        val extensionKeys = mutableSetOf<String>()
        collectExtensionKeys(card.extensions, extensionKeys, 0)
        if (extensionKeys.any { it.contains("regex") }) add("contains regex-replacement extensions; the data is preserved and replacement rules do not run.")
        if (extensionKeys.any { it.contains("script") }) add("contains script extensions; the data is preserved and scripts do not run.")
        card.extensions["depth_prompt"]?.takeUnless { it == JsonNull }?.let { value ->
            if (value !is JsonObject || value.text("prompt").isNotBlank() && card.depthPrompt == null) {
                add("the character depth note parameters are unsupported, so this note was skipped.")
            }
        }
        val unsupported = CharacterWorldbook.unsupportedEntries(card)
        if (unsupported.isNotEmpty()) add("${unsupported.size} worldbook entries use unsupported trigger conditions, so these entries were skipped.")
        if ((card.data["assets"] as? JsonArray)?.isNotEmpty() == true) add("the attached asset manifest is preserved; this version uses the character card main image and does not load extra assets.")
        if (card.data.strings("group_only_greetings").isNotEmpty()) add("group-chat-specific greetings are preserved; this version is for single-character dialogue only.")
    }

    private fun collectExtensionKeys(value: JsonElement, keys: MutableSet<String>, depth: Int) {
        if (depth >= 4 || value !is JsonObject) return
        value.forEach { (key, child) ->
            val present = when (child) {
                JsonNull -> false
                is JsonArray -> child.isNotEmpty()
                is JsonObject -> child.isNotEmpty()
                is JsonPrimitive -> child.content.isNotBlank() && child.content != "false"
            }
            if (present) keys += key.lowercase()
            collectExtensionKeys(child, keys, depth + 1)
        }
    }
}
