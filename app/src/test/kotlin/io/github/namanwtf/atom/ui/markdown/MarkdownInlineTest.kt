package io.github.namanwtf.atom.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.namanwtf.atom.ui.components.AtomMonospace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * relaxed rules for inline rendering in chat: models often write `**bold.**then text`, which under CommonMark flanking
 * rules do not form emphasis (the parser leaves lone delimiters), so the renderer pairs them loosely according to the writer's intent.
 */
class MarkdownInlineTest {

    @Test
    fun boldClosingBetweenCjkPunctuationAndTextIsPairedByIntent() {
        val paragraph = paragraphOf("**This does not represent your real device.** but you study")

        assertEquals("This does not represent your real device. but you study", paragraph.text.text)
        assertTrue(paragraph.text.hasSpanCovering("This does not represent your real device.") { it.fontWeight == FontWeight.Bold })
        assertFalse(paragraph.text.hasSpanCovering("but you study") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun boldFollowedImmediatelyByCjkTextIsPairedByIntent() {
        val paragraph = paragraphOf("**I suggest you don't turn your holiday into \"memorizing the whole big-tech question bank\".** first get ready:")

        assertFalse(paragraph.text.text.contains('*'))
        assertTrue(paragraph.text.hasSpanCovering("I suggest you don't turn your holiday into \"memorizing the whole big-tech question bank\".") { it.fontWeight == FontWeight.Bold })
        assertFalse(paragraph.text.hasSpanCovering("first get ready") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun boldAroundInlineCodeInCjkSentenceIsPairedByIntent() {
        val paragraph = paragraphOf("found that **Android 17's `mmd` (memory management daemon)** is worth knowing about")

        assertFalse(paragraph.text.text.contains('*'))
        assertTrue(paragraph.text.hasSpanCovering("memory management daemon") { it.fontWeight == FontWeight.Bold })
        assertTrue(paragraph.text.hasSpanCovering("mmd") { it.fontFamily == AtomMonospace })
    }

    @Test
    fun unmatchedDelimiterStaysLiteral() {
        val paragraph = paragraphOf("unclosed **marker")

        assertEquals("unclosed **marker", paragraph.text.text)
        assertFalse(paragraph.text.hasSpanCovering("marker") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun spacedAsterisksStayLiteral() {
        val paragraph = paragraphOf("2 * 3 * 4")

        assertEquals("2 * 3 * 4", paragraph.text.text)
        assertTrue(paragraph.text.spanStyles.isEmpty())
    }

    @Test
    fun looseSingleStarPairsAsItalic() {
        val paragraph = paragraphOf("this is *italic* content")

        assertFalse(paragraph.text.text.contains('*'))
        assertTrue(paragraph.text.hasSpanCovering("italic") { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun looseTildePairsAsStrikethrough() {
        val paragraph = paragraphOf("this is ~~strikethrough~~ content")

        assertFalse(paragraph.text.text.contains('~'))
        assertTrue(paragraph.text.hasSpanCovering("strikethrough") { it.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun parserStrongKeepsWorkingWithoutLoosePairing() {
        val paragraph = paragraphOf("plain **bold** plain")

        assertEquals("plain bold plain", paragraph.text.text)
        assertTrue(paragraph.text.hasSpanCovering("bold") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun nestedLooseEmphasisPairsIndependently() {
        val paragraph = paragraphOf("**bold *italic* end** then")

        assertFalse(paragraph.text.text.contains('*'))
        assertTrue(paragraph.text.hasSpanCovering("end") { it.fontWeight == FontWeight.Bold })
        assertTrue(paragraph.text.hasSpanCovering("italic") { it.fontStyle == FontStyle.Italic })
    }

    @Test
    fun streamingPrefixAndFinalTextRenderTheSameBold() {
        val partial = paragraphOf("**it.", isComplete = false)
        val complete = paragraphOf("**it.** but", isComplete = true)

        assertTrue(partial.text.hasSpanCovering("it.") { it.fontWeight == FontWeight.Bold })
        assertEquals("it. but", complete.text.text)
        assertTrue(complete.text.hasSpanCovering("it.") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun kbdTagRendersContentAsInlineCode() {
        val paragraph = paragraphOf("press <kbd>Ctrl</kbd> to continue")

        assertFalse(paragraph.text.text.contains('<'))
        assertTrue(paragraph.text.text.contains("Ctrl"))
        assertTrue(paragraph.text.hasSpanCovering("Ctrl") { it.fontFamily == AtomMonospace })
    }

    private fun paragraphOf(source: String, isComplete: Boolean = true): MarkdownParagraph =
        StreamingGfmParserSession().parse(source, isComplete = isComplete)
            .document.blocks.filterIsInstance<MarkdownParagraph>().single()

    private fun AnnotatedString.hasSpanCovering(
        substring: String,
        predicate: (SpanStyle) -> Boolean,
    ): Boolean {
        val start = text.indexOf(substring)
        if (start < 0) return false
        val end = start + substring.length
        return spanStyles.any { range ->
            range.start <= start && range.end >= end && predicate(range.item)
        }
    }
}
