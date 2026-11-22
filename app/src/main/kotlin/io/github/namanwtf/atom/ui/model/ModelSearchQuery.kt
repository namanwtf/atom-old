package io.github.namanwtf.atom.ui.model

/**
 * Unified matching rules for model search: split on non-alphanumerics into multiple words, each of which must appear in order within some field.
 *
 * Fields likewise drop separators before comparison, so `v4flash` can match `DeepSeek V4.1 Flash`,
 * and `deepseek-v3` and `deepseek v3` yield the same results.
 */
internal class ModelSearchQuery(query: String) {
    private val tokens = query.lowercase().split(separators).filter(String::isNotBlank)

    val isEmpty: Boolean
        get() = tokens.isEmpty()

    fun matches(vararg fields: String): Boolean {
        if (tokens.isEmpty()) return true
        val normalized = fields.map { field -> field.lowercase().filter(Char::isLetterOrDigit) }
        return tokens.all { token -> normalized.any { field -> field.containsCharactersInOrder(token) } }
    }

    private companion object {
        val separators = Regex("""[^\p{L}\p{N}]+""")
    }
}

private fun String.containsCharactersInOrder(query: String): Boolean {
    var queryIndex = 0
    for (character in this) {
        if (character == query[queryIndex]) {
            queryIndex++
            if (queryIndex == query.length) return true
        }
    }
    return false
}
