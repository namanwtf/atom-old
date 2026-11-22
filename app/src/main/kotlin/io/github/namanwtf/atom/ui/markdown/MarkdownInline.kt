package io.github.namanwtf.atom.ui.markdown

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import io.github.namanwtf.atom.ui.components.AtomMonospace
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.LinkMap

/**
 * The theme-dependent part of inline styling. It must be settled when building the AnnotatedString in the background,
 * so it's part of the parse input: a theme switch invalidates and rebuilds the cache, rather than recoloring per span at draw time.
 */
@Immutable
internal data class MarkdownInlineStyle(
    val linkColor: Color,
    val codeBackground: Color,
    val strongColor: Color = Color.Unspecified,
    val codeColor: Color = Color.Unspecified,
) {
    companion object {
        val Default = MarkdownInlineStyle(
            linkColor = Color(0xFF3482FF),
            codeBackground = Color(0x1F808080),
        )
    }
}

internal data class MarkdownInlineContent(
    val text: AnnotatedString,
    val inlineMath: List<InlineMathSpec> = emptyList(),
)

/**
 * Converts a group of inline nodes into an AnnotatedString and a list of laid-out inline formulas.
 *
 * Handles only the GFM inline syntax that actually appears in chat; inline math is pre-laid-out via RaTeX then written to [appendInlineContent],
 * using the original delimiter source as `alternateText` for select-to-copy and accessibility; failed or unrecognized leaves output verbatim,
 * ensuring no content ever vanishes.
 */
internal class MarkdownInlineBuilder(
    private val source: String,
    private val originalSource: String = source,
    private val links: LinkMap?,
    private val style: MarkdownInlineStyle,
    private val mathCache: MarkdownMathLayoutCache = markdownMathLayoutCache,
) {
    private val inlineMathSpecs = ArrayList<InlineMathSpec>()
    private var lastAppendedChar: Char = '\u0000'

    fun build(nodes: List<ASTNode>): MarkdownInlineContent {
        inlineMathSpecs.clear()
        lastAppendedChar = '\u0000'
        val builder = AnnotatedString.Builder()
        val state = LineState()
        builder.appendNodes(nodes, parent = null, state)
        val trimmed = builder.toAnnotatedString().trimTrailingWhitespace()
        val retainedMath = if (inlineMathSpecs.isEmpty()) {
            emptyList()
        } else {
            inlineMathSpecs.filter { it.rangeEnd <= trimmed.length }
        }
        return MarkdownInlineContent(text = trimmed, inlineMath = retainedMath)
    }

    /** Indentation and quote prefix (`>`) after a soft break belong to block structure, not the body. */
    private class LineState {
        var atLineStart = true
        /** The EOL immediately following a hard break (two trailing spaces or a backslash) has already been output by the hard break. */
        var afterHardBreak = false
    }

    private fun AnnotatedString.Builder.appendNode(node: ASTNode, parent: ASTNode?, state: LineState) {
        val type = node.type
        if (state.atLineStart && (type == MarkdownTokenTypes.WHITE_SPACE || type == MarkdownTokenTypes.BLOCK_QUOTE)) {
            return
        }
        if (type == MarkdownTokenTypes.EOL || type == MarkdownTokenTypes.HARD_LINE_BREAK) {
            // A single newline in chat output is almost always the author's intended break; folding it to a space per CommonMark would
            // insert extra whitespace between CJK text and squeeze deliberately line-broken content into one paragraph.
            if (!(type == MarkdownTokenTypes.EOL && state.afterHardBreak)) appendTracked('\n')
            state.afterHardBreak = type == MarkdownTokenTypes.HARD_LINE_BREAK
            state.atLineStart = true
            return
        }
        state.afterHardBreak = false
        when (type) {
            MarkdownTokenTypes.WHITE_SPACE -> appendTracked(' ')
            MarkdownElementTypes.STRONG -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, color = style.strongColor),
            ) {
                appendChildren(node, state)
            }
            MarkdownElementTypes.EMPH -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendChildren(node, state)
            }
            GFMElementTypes.STRIKETHROUGH -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendChildren(node, state)
            }
            MarkdownElementTypes.CODE_SPAN -> appendCode(codeSpanText(node))
            GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH -> appendMath(node)
            MarkdownElementTypes.INLINE_LINK -> appendInlineLink(node, state)
            MarkdownElementTypes.FULL_REFERENCE_LINK -> appendReferenceLink(
                node = node,
                textNode = node.child(MarkdownElementTypes.LINK_TEXT),
                labelNode = node.child(MarkdownElementTypes.LINK_LABEL),
                state = state,
            )
            MarkdownElementTypes.SHORT_REFERENCE_LINK -> appendReferenceLink(
                node = node,
                textNode = node.child(MarkdownElementTypes.LINK_LABEL),
                labelNode = node.child(MarkdownElementTypes.LINK_LABEL),
                state = state,
            )
            MarkdownElementTypes.AUTOLINK -> {
                val url = node.children.firstOrNull { it.type == MarkdownTokenTypes.AUTOLINK }?.text() ?: node.text()
                appendUrl(url.removeSurrounding("<", ">"))
            }
            GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.AUTOLINK, MarkdownTokenTypes.EMAIL_AUTOLINK ->
                appendUrl(node.text())
            MarkdownElementTypes.IMAGE -> appendImage(node, state)
            MarkdownTokenTypes.HTML_TAG -> appendHtmlTag(node, state)
            else -> when {
                isDelimiterOf(type, parent) -> Unit
                node.children.isNotEmpty() -> appendChildren(node, state)
                else -> appendTracked(decodeMarkdownText(node.text()))
            }
        }
        state.atLineStart = false
    }

    private fun AnnotatedString.Builder.appendChildren(node: ASTNode, state: LineState) {
        appendNodes(node.children, parent = node, state)
    }

    /**
     * Outputs content by sibling node list. Model output commonly has patterns like `**bold.**followed text`: under CommonMark's
     * flanking rules, a delimiter between CJK punctuation and text doesn't form emphasis, leaving the parser with stray
     * EMPH/TILDE tokens. Chat treats this by writing intent: do one loose pairing over same-level siblings, requiring only that
     * the content adjacent to the token isn't whitespace; unpaired tokens still output verbatim, dropping no characters.
     */
    private fun AnnotatedString.Builder.appendNodes(nodes: List<ASTNode>, parent: ASTNode?, state: LineState) {
        // When it's already a child of valid emphasis, the token is a real delimiter filtered by isDelimiterOf, and doesn't participate in loose pairing.
        val pairs = if (parent?.type in REAL_EMPHASIS_TYPES) emptyMap() else pairLooseInline(nodes)
        var index = 0
        while (index < nodes.size) {
            val pair = pairs[index]
            if (pair != null) {
                withStyle(pair.style) {
                    if (pair.padSpaces) appendTracked(' ')
                    appendNodes(nodes.subList(pair.contentStart, pair.contentEnd), parent, state)
                    if (pair.padSpaces) appendTracked(' ')
                }
                index = pair.nextIndex
            } else {
                appendNode(nodes[index], parent, state)
                index += 1
            }
        }
    }

    /** A sibling-node range reinterpreted as a style; the two end tokens themselves output no text. */
    private class PairedInline(
        val contentStart: Int,
        val contentEnd: Int,
        val nextIndex: Int,
        val style: SpanStyle,
        /** The inline-code style's background needs surrounding spaces to spread out, not hugging the glyph. */
        val padSpaces: Boolean = false,
    )

    private fun pairLooseInline(nodes: List<ASTNode>): Map<Int, PairedInline> =
        pairLooseDelimiters(nodes) + pairKbdTags(nodes)

    private fun pairLooseDelimiters(nodes: List<ASTNode>): Map<Int, PairedInline> {
        data class Run(val start: Int, val char: Char, val length: Int)

        val runs = mutableListOf<Run>()
        var index = 0
        while (index < nodes.size) {
            val char = when (nodes[index].type) {
                MarkdownTokenTypes.EMPH -> '*'
                GFMTokenTypes.TILDE -> '~'
                else -> null
            }
            if (char == null) {
                index += 1
                continue
            }
            var end = index
            while (end + 1 < nodes.size && nodes[end + 1].type == nodes[index].type) end += 1
            val length = end - index + 1
            // `*`/`**` pair as italic/bold respectively, `~~` pairs as strikethrough; a single `~` is common in paths and doesn't pair.
            val eligible = if (char == '*') length <= 2 else length == 2
            if (eligible) runs += Run(index, char, length)
            index = end + 1
        }

        fun ASTNode?.isBlankLike(): Boolean = when (this?.type) {
            null -> true
            MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.EOL, MarkdownTokenTypes.HARD_LINE_BREAK -> true
            else -> text().isBlank()
        }

        val openers = ArrayDeque<Run>()
        val pairs = mutableMapOf<Int, PairedInline>()
        for (run in runs) {
            val canOpen = !nodes.getOrNull(run.start + run.length).isBlankLike()
            val canClose = !nodes.getOrNull(run.start - 1).isBlankLike()
            val opener = if (canClose) {
                openers.lastOrNull { it.char == run.char && it.length == run.length }
            } else {
                null
            }
            if (opener != null) {
                openers.remove(opener)
                val style = when {
                    run.char == '~' -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    run.length == 2 -> SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = this@MarkdownInlineBuilder.style.strongColor,
                    )
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                pairs[opener.start] = PairedInline(
                    contentStart = opener.start + opener.length,
                    contentEnd = run.start,
                    nextIndex = run.start + run.length,
                    style = style,
                )
            } else if (canOpen) {
                openers.addLast(run)
            }
        }
        return pairs
    }

    /** `<kbd>` is the only inline HTML commonly seen in chat output: after pairing, its content displays in inline-code style. */
    private fun pairKbdTags(nodes: List<ASTNode>): Map<Int, PairedInline> {
        val open = ArrayDeque<Int>()
        val pairs = mutableMapOf<Int, PairedInline>()
        nodes.forEachIndexed { index, node ->
            if (node.type != MarkdownTokenTypes.HTML_TAG) return@forEachIndexed
            val tag = node.text()
            when {
                KBD_OPEN.matches(tag) -> open.addLast(index)
                KBD_CLOSE.matches(tag) && open.isNotEmpty() -> {
                    val start = open.removeLast()
                    if (start + 1 < index) {
                        pairs[start] = PairedInline(
                            contentStart = start + 1,
                            contentEnd = index,
                            nextIndex = index + 1,
                            style = SpanStyle(
                                fontFamily = AtomMonospace,
                                fontSize = 0.87.em,
                                color = style.codeColor,
                                background = style.codeBackground,
                            ),
                            padSpaces = true,
                        )
                    }
                }
            }
        }
        return pairs
    }

    private fun AnnotatedString.Builder.appendCode(code: String) {
        withStyle(
            SpanStyle(
                fontFamily = AtomMonospace,
                fontSize = 0.87.em,
                color = style.codeColor,
                background = style.codeBackground,
            ),
        ) {
            // Narrow spaces before/after keep the background from hugging the glyph edges, reading like a standalone code token.
            appendTracked(' ')
            appendTracked(code)
            appendTracked(' ')
        }
    }

    private fun AnnotatedString.Builder.appendMath(node: ASTNode) {
        val latex = mathText(node)
        val rawSource = originalMathSource(node)
        val displayList = mathCache.layout(latex = latex, displayMode = false)
        if (displayList == null) {
            // On parse failure or native-engine unavailability, keep the full delimiter-wrapped source, hiding or truncating no characters.
            appendTracked(rawSource)
            return
        }
        val leadingEm = if (lastAppendedChar.needsCjkMathKerning()) INLINE_CJK_MATH_GAP_EM else 0f
        val trailingEm = if (peekNextVisibleCharAfter(node.endOffset).needsCjkMathKerning()) {
            INLINE_CJK_MATH_GAP_EM
        } else {
            0f
        }
        // StaticLayout truncates a single-line PlaceholderSpan at hard breaks; a single newline inside a TeX inline formula is equivalent to a space.
        val alternateText = rawSource.replace("\r\n", " ").replace('\n', ' ').ifEmpty { latex }
        val id = "atom_inline_math_${inlineMathSpecs.size}"
        val rangeStart = length
        appendInlineContent(id = id, alternateText = alternateText)
        val rangeEnd = length
        lastAppendedChar = alternateText.last()
        inlineMathSpecs += InlineMathSpec(
            id = id,
            latex = latex,
            rawSource = rawSource,
            displayList = displayList,
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            leadingSpaceEm = leadingEm,
            trailingSpaceEm = trailingEm,
        )
    }

    private fun AnnotatedString.Builder.appendInlineLink(node: ASTNode, state: LineState) {
        val textNode = node.child(MarkdownElementTypes.LINK_TEXT)
        val destination = node.child(MarkdownElementTypes.LINK_DESTINATION)?.text()
            ?.removeSurrounding("<", ">")
            ?.let(::decodeMarkdownText)
        appendLink(url = destination, textNode = textNode, fallback = node, state = state)
    }

    private fun AnnotatedString.Builder.appendReferenceLink(
        node: ASTNode,
        textNode: ASTNode?,
        labelNode: ASTNode?,
        state: LineState,
    ) {
        val destination = labelNode?.let { links?.getLinkInfo(it.text()) }?.destination?.toString()
        if (destination == null) {
            // Without a matching definition it's just ordinary bracketed text, kept verbatim.
            appendTracked(decodeMarkdownText(node.text()))
            return
        }
        appendLink(url = destination, textNode = textNode, fallback = node, state = state)
    }

    private fun AnnotatedString.Builder.appendLink(
        url: String?,
        textNode: ASTNode?,
        fallback: ASTNode,
        state: LineState,
    ) {
        val safeUrl = url?.let(::safeLinkUrl)
        val label: AnnotatedString.Builder.() -> Unit = {
            if (textNode != null) {
                appendNodes(
                    textNode.children.filterNot {
                        it.type == MarkdownTokenTypes.LBRACKET || it.type == MarkdownTokenTypes.RBRACKET
                    },
                    parent = textNode,
                    state = state,
                )
            } else {
                appendTracked(decodeMarkdownText(fallback.text()))
            }
        }
        if (safeUrl == null) {
            label()
        } else {
            withLink(linkAnnotation(safeUrl)) { label() }
        }
    }

    private fun AnnotatedString.Builder.appendUrl(raw: String) {
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        val safeUrl = safeLinkUrl(url)
        if (safeUrl == null) appendTracked(raw) else withLink(linkAnnotation(safeUrl)) { appendTracked(raw) }
    }

    /** Chat doesn't load remote images: use the alt text as a tappable link, losing no content and making no network request. */
    private fun AnnotatedString.Builder.appendImage(node: ASTNode, state: LineState) {
        val link = node.child(MarkdownElementTypes.INLINE_LINK)
        val alt = link?.child(MarkdownElementTypes.LINK_TEXT)?.text()?.removeSurrounding("[", "]").orEmpty()
        val destination = link?.child(MarkdownElementTypes.LINK_DESTINATION)?.text()?.removeSurrounding("<", ">")
        val label = alt.ifBlank { destination.orEmpty() }
        val safeUrl = destination?.let(::safeLinkUrl)
        if (safeUrl == null) appendTracked(label) else withLink(linkAnnotation(safeUrl)) { appendTracked(label) }
        state.atLineStart = false
    }

    private fun AnnotatedString.Builder.appendHtmlTag(node: ASTNode, state: LineState) {
        val tag = node.text()
        if (BR_TAG.matches(tag)) {
            appendTracked('\n')
            state.atLineStart = true
        } else {
            appendTracked(tag)
        }
    }

    private fun AnnotatedString.Builder.appendTracked(char: Char) {
        append(char)
        lastAppendedChar = char
    }

    private fun AnnotatedString.Builder.appendTracked(text: String) {
        if (text.isEmpty()) return
        append(text)
        lastAppendedChar = text.last()
    }

    private fun linkAnnotation(url: String) = LinkAnnotation.Url(
        url = url,
        styles = TextLinkStyles(
            style = SpanStyle(color = style.linkColor, fontWeight = FontWeight.Medium),
            pressedStyle = SpanStyle(
                color = style.linkColor,
                fontWeight = FontWeight.Medium,
                background = style.linkColor.copy(alpha = 0.12f),
            ),
        ),
    )

    private fun codeSpanText(node: ASTNode): String {
        val children = node.children
        if (children.size < 2) return ""
        val inner = source.substring(children.first().endOffset, children.last().startOffset)
            .replace('\n', ' ')
        // CommonMark: when both ends have a space and the content isn't all spaces, strip one from each side, used to wrap backticks.
        return if (inner.length >= 2 && inner.first() == ' ' && inner.last() == ' ' && inner.isNotBlank()) {
            inner.substring(1, inner.length - 1)
        } else {
            inner
        }
    }

    private fun mathText(node: ASTNode): String {
        val children = node.children
        if (children.size < 2) return node.text()
        return source.substring(children.first().endOffset, children.last().startOffset).trim()
    }

    private fun originalMathSource(node: ASTNode): String =
        if (node.endOffset <= originalSource.length) {
            originalSource.substring(node.startOffset, node.endOffset)
        } else {
            node.text()
        }

    private fun peekNextVisibleCharAfter(offset: Int): Char {
        var cursor = offset
        while (cursor < source.length) {
            val ch = source[cursor]
            if (ch == '*' || ch == '_' || ch == '~') {
                cursor += 1
                continue
            }
            return ch
        }
        return '\u0000'
    }

    private fun isDelimiterOf(type: IElementType, parent: ASTNode?): Boolean = when (parent?.type) {
        MarkdownElementTypes.STRONG, MarkdownElementTypes.EMPH -> type == MarkdownTokenTypes.EMPH
        GFMElementTypes.STRIKETHROUGH -> type == GFMTokenTypes.TILDE
        else -> false
    }

    private fun ASTNode.text(): String = source.substring(startOffset, endOffset)

    private companion object {
        const val INLINE_CJK_MATH_GAP_EM = 0.11f
        val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        val KBD_OPEN = Regex("""<kbd\s*>""", RegexOption.IGNORE_CASE)
        val KBD_CLOSE = Regex("""</kbd\s*>""", RegexOption.IGNORE_CASE)

        /** These nodes' EMPH/TILDE children are real delimiters already consumed, and don't participate in loose pairing. */
        val REAL_EMPHASIS_TYPES = setOf(
            MarkdownElementTypes.STRONG,
            MarkdownElementTypes.EMPH,
            GFMElementTypes.STRIKETHROUGH,
        )
    }
}

private fun Char.needsCjkMathKerning(): Boolean {
    if (this == '\u0000' || isWhitespace() || code < 0x2E80) return false
    return when (Character.UnicodeScript.of(code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        -> true
        else -> false
    }
}

internal fun ASTNode.child(type: IElementType): ASTNode? = children.firstOrNull { it.type == type }

/** Only allow clearly safe protocols; other schemes display as plain text and aren't handed to the system to open. */
internal fun safeLinkUrl(url: String): String? {
    val trimmed = url.trim()
    val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
    return trimmed.takeIf { scheme in SAFE_LINK_SCHEMES }
}

private val SAFE_LINK_SCHEMES = setOf("http", "https", "mailto")

/**
 * Handles CommonMark backslash escapes and HTML entities. The parser keeps them in TEXT leaves,
 * and they must be restored before rendering; the library's built-in converter outputs HTML-escaped text, unsuitable for direct display.
 */
internal fun decodeMarkdownText(text: String): String {
    if ('\\' !in text && '&' !in text) return text
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '\\' && index + 1 < text.length && text[index + 1] in ESCAPABLE) {
            out.append(text[index + 1])
            index += 2
            continue
        }
        if (char == '&') {
            val end = text.indexOf(';', index)
            val decoded = if (end in index + 2..index + 10) decodeEntity(text.substring(index + 1, end)) else null
            if (decoded != null) {
                out.append(decoded)
                index = end + 1
                continue
            }
        }
        out.append(char)
        index += 1
    }
    return out.toString()
}

private fun decodeEntity(name: String): String? = when {
    name.startsWith("#x", ignoreCase = true) -> name.substring(2).toIntOrNull(16)?.toCodePointString()
    name.startsWith("#") -> name.substring(1).toIntOrNull()?.toCodePointString()
    else -> NAMED_ENTITIES[name]
}

private fun Int.toCodePointString(): String? =
    takeIf { Character.isValidCodePoint(it) && it != 0 }?.let { String(Character.toChars(it)) }

private const val ESCAPABLE = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private val NAMED_ENTITIES = mapOf(
    "amp" to "&",
    "lt" to "<",
    "gt" to ">",
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "copy" to "©",
    "reg" to "®",
    "trade" to "™",
    "hellip" to "…",
    "mdash" to "—",
    "ndash" to "–",
    "times" to "×",
    "middot" to "·",
)

private fun AnnotatedString.trimTrailingWhitespace(): AnnotatedString {
    var end = length
    while (end > 0 && text[end - 1].isWhitespace()) end -= 1
    return if (end == length) this else subSequence(0, end)
}

/** This is the only entry when building inline text, keeping paragraph, heading, and table-cell rules consistent. */
internal fun inlineContent(
    nodes: List<ASTNode>,
    source: String,
    originalSource: String = source,
    links: LinkMap?,
    style: MarkdownInlineStyle,
    mathCache: MarkdownMathLayoutCache = markdownMathLayoutCache,
): MarkdownInlineContent = MarkdownInlineBuilder(
    source = source,
    originalSource = originalSource,
    links = links,
    style = style,
    mathCache = mathCache,
).build(nodes)

internal fun inlineText(
    nodes: List<ASTNode>,
    source: String,
    links: LinkMap?,
    style: MarkdownInlineStyle,
): AnnotatedString = inlineContent(
    nodes = nodes,
    source = source,
    links = links,
    style = style,
).text
