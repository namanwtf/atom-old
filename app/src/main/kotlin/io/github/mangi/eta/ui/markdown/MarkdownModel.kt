package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import io.ratex.DisplayList

/**
 * A parsed Markdown document: built from the GFM AST on a background thread, while Compose only draws.
 *
 * Every block uses its source start offset [MarkdownBlock.offset] as identity: streaming output only appends at the tail,
 * so completed blocks' offsets and content never change again, the builder reuses the same instance, and composition skips them directly.
 */
@Immutable
internal class MarkdownDocument(
    val blocks: List<MarkdownBlock>,
    /** All blocks in the document that participate in character-by-character reveal, used to let the reveal coordinator drop old blocks that have disappeared. */
    val revealKeys: Set<RevealBlockKey>,
    /** Link and inline-code colors are written at build time; old documents can't be reused directly after a theme change. */
    val inlineStyle: MarkdownInlineStyle,
)

@Immutable
internal sealed interface MarkdownBlock {
    val offset: Int
}

/** A leaf block carrying a span of rich text; [revealKey] is its source offset. */
internal sealed interface MarkdownTextBlock : MarkdownBlock {
    val text: AnnotatedString
    val inlineMath: List<InlineMathSpec> get() = emptyList()
    val revealKey: RevealBlockKey get() = RevealBlockKey(offset)
}

@Immutable
internal data class InlineMathSpec(
    val id: String,
    val latex: String,
    val rawSource: String,
    val displayList: DisplayList,
    /** A character range within [MarkdownTextBlock.text] that reveal treats as a single indivisible atomic step. */
    val rangeStart: Int,
    val rangeEnd: Int,
    /** Compensating micro-offset (in em) when adjacent to a CJK character on the left with no space. */
    val leadingSpaceEm: Float = 0f,
    /** Compensating micro-offset (in em) when adjacent to a CJK character on the right with no space. */
    val trailingSpaceEm: Float = 0f,
)

internal data class MarkdownParagraph(
    override val offset: Int,
    override val text: AnnotatedString,
    override val inlineMath: List<InlineMathSpec> = emptyList(),
) : MarkdownTextBlock

internal data class MarkdownHeading(
    override val offset: Int,
    val level: Int,
    override val text: AnnotatedString,
    override val inlineMath: List<InlineMathSpec> = emptyList(),
) : MarkdownTextBlock

internal data class MarkdownCode(
    override val offset: Int,
    val language: String?,
    override val text: AnnotatedString,
) : MarkdownTextBlock

/**
 * A standalone block-level math formula (`$$...$$` or `\[...\]`).
 *
 * [displayList] is pre-laid-out via RaTeX on a background thread; it is `null` on parse failure or when the native engine is unavailable,
 * and the UI falls back to showing [rawSource] verbatim, ensuring formula content is never lost and always copyable.
 */
@Immutable
internal data class MarkdownMath(
    override val offset: Int,
    val latex: String,
    val rawSource: String,
    val displayList: DisplayList?,
) : MarkdownBlock {
    val revealKey: RevealBlockKey get() = RevealBlockKey(offset)
}

internal data class MarkdownQuote(
    override val offset: Int,
    val blocks: List<MarkdownBlock>,
) : MarkdownBlock

internal data class MarkdownAlert(
    override val offset: Int,
    val kind: MarkdownAlertKind,
    val blocks: List<MarkdownBlock>,
) : MarkdownBlock

internal enum class MarkdownAlertKind { Note, Tip, Important, Warning, Caution }

internal data class MarkdownList(
    override val offset: Int,
    val ordered: Boolean,
    /** Loose lists keep paragraph-level spacing between items; compact lists keep only line spacing. */
    val loose: Boolean,
    val items: List<MarkdownListItem>,
) : MarkdownBlock

internal data class MarkdownListItem(
    val offset: Int,
    /** The ordinal of an ordered list item; null for unordered lists. */
    val number: Int?,
    /** The checked state of a task list item; null for ordinary list items. */
    val checked: Boolean?,
    val blocks: List<MarkdownBlock>,
    /** The marker appears together with the first text block, so a lone bullet doesn't pop out first during streaming. */
    val markerRevealKey: RevealBlockKey?,
)

internal data class MarkdownTable(
    override val offset: Int,
    val alignments: List<MarkdownColumnAlign>,
    val header: List<MarkdownTableCell>,
    val rows: List<List<MarkdownTableCell>>,
) : MarkdownBlock

internal data class MarkdownTableCell(
    override val offset: Int,
    override val text: AnnotatedString,
    override val inlineMath: List<InlineMathSpec> = emptyList(),
) : MarkdownTextBlock

internal enum class MarkdownColumnAlign { Start, Center, End }

internal data class MarkdownRule(override val offset: Int) : MarkdownBlock
