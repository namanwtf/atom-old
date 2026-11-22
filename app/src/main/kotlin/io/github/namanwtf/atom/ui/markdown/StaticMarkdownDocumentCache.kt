package io.github.namanwtf.atom.ui.markdown

import android.util.LruCache

internal val staticMarkdownDocumentCache = StaticMarkdownDocumentCache()

/**
 * Cache weight uses UTF-16 source character count, and counts a minimum weight for short documents to bound the entry count.
 * This is not an exact heap-byte budget: the amplification of the syntax tree and rich-text objects varies with content structure.
 */
internal class StaticMarkdownDocumentCache(
    sourceCharBudget: Int = 512 * 1024,
    maxEntries: Int = 96,
    private val maxDocumentSourceChars: Int = (sourceCharBudget / 4).coerceAtLeast(1),
) {
    init {
        require(sourceCharBudget > 0 && maxEntries > 0)
        require(maxDocumentSourceChars in 1..sourceCharBudget)
    }

    private val minimumEntryWeight =
        ((sourceCharBudget.toLong() + maxEntries - 1) / maxEntries).toInt()
    private val cache = object : LruCache<Pair<String, MarkdownInlineStyle>, MarkdownDocument>(sourceCharBudget) {
        override fun sizeOf(key: Pair<String, MarkdownInlineStyle>, value: MarkdownDocument): Int =
            key.first.length.coerceAtLeast(minimumEntryWeight)
    }

    fun get(content: String, style: MarkdownInlineStyle): MarkdownDocument? = cache.get(content to style)

    fun put(snapshot: StreamingGfmSnapshot) {
        if (snapshot.isComplete) admit(snapshot.originalSource, snapshot.document)
    }

    fun parse(content: String, style: MarkdownInlineStyle): MarkdownDocument =
        StreamingGfmParserSession().parse(content, isComplete = true, style = style).document
            .also { admit(content, it) }

    private fun admit(content: String, document: MarkdownDocument) {
        // Huge replies still display in full but don't evict the whole batch of small documents; streaming handoff and static parsing share the same admission boundary.
        if (content.length > maxDocumentSourceChars) return
        cache.put(content to document.inlineStyle, document)
    }
}
