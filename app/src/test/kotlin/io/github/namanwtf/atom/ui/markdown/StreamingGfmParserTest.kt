package io.github.namanwtf.atom.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.namanwtf.atom.ui.components.AtomMonospace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingGfmParserTest {
    @Test
    fun headingIsParsedAsHeadingFromFirstStreamingSnapshot() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "## Title",
            isComplete = false,
        )

        assertEquals("## Title", snapshot.renderedSource)
        val heading = snapshot.document.blocks.single() as MarkdownHeading
        assertEquals(2, heading.level)
        assertEquals("Title", heading.text.text)
    }

    @Test
    fun tableHeaderIsBufferedUntilDelimiterConfirmsTheBlock() {
        assertEquals(
            "",
            StreamingGfmProjection.project(
                source = "| Metric | Value |",
                isComplete = false,
            ),
        )
        assertEquals(
            "",
            StreamingGfmProjection.project(
                source = "| Metric | Value |\n| --",
                isComplete = false,
            ),
        )

        val confirmed = "| Metric | Value |\n| --- | --- |"
        val snapshot = StreamingGfmParserSession().parse(
            source = confirmed,
            isComplete = false,
        )

        assertEquals(confirmed, snapshot.renderedSource)
        val table = snapshot.document.blocks.single() as MarkdownTable
        assertEquals(listOf("Metric", "Value"), table.header.map { cell -> cell.text.text })
    }

    @Test
    fun ordinaryPipeTextIsReleasedWhenNextLineCannotBeATableDelimiter() {
        val source = "please choose A | B\nthis is not a separator row"

        assertEquals(
            source,
            StreamingGfmProjection.project(source = source, isComplete = false),
        )
    }

    @Test
    fun incompleteStrongDelimiterUsesVirtualEofClosure() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "description **stress",
            isComplete = false,
        )

        assertEquals("description **stress**", snapshot.renderedSource)
        val paragraph = snapshot.document.blocks.single() as MarkdownParagraph
        assertTrue(paragraph.text.hasSpanCovering("stress") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun incompleteInlineCodeUsesVirtualEofClosure() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "run `adb shell",
            isComplete = false,
        )

        assertEquals("run `adb shell`", snapshot.renderedSource)
        val paragraph = snapshot.document.blocks.single() as MarkdownParagraph
        assertTrue(paragraph.text.hasSpanCovering("adb shell") { it.fontFamily == AtomMonospace })
    }

    @Test
    fun incompleteLinkIsNotPublishedAsRawMarkdown() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "see [official docs](https://example.com/do",
            isComplete = false,
        )

        assertEquals("see ", snapshot.renderedSource)
        assertFalse(snapshot.renderedSource.contains('['))
    }

    @Test
    fun openFenceRendersAsCodeWithoutChangingStoredSource() {
        val source = "```kotlin\nval answer = 42"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = false,
        )

        assertEquals(source, snapshot.originalSource)
        assertTrue(snapshot.renderedSource.endsWith("\n```"))
        val code = snapshot.document.blocks.single() as MarkdownCode
        assertEquals("kotlin", code.language)
        assertEquals("val answer = 42", code.text.text)
    }

    @Test
    fun completionParsesExactOriginalSourceWithoutVirtualCharacters() {
        val source = "unclosed **marker"
        val snapshot = StreamingGfmParserSession().parse(
            source = source,
            isComplete = true,
        )

        assertEquals(source, snapshot.renderedSource)
        assertTrue(snapshot.isComplete)
    }

    @Test
    fun stableHandoffKeepsTheCompletedDocumentAndFormattedBlocks() {
        val parser = StreamingGfmParserSession()
        parser.parse("## Result\n\n```kotlin\nval answer = 42", isComplete = false)
        val content = "## Result\n\n```kotlin\nval answer = 42\n```\n\n**Done**"
        val snapshot = parser.parse(content, isComplete = true)
        val stableDocument = snapshot.completedDocumentFor(content)

        assertNotNull(stableDocument)
        assertSame(snapshot.document, stableDocument)
        val blocks = stableDocument!!.blocks
        assertTrue(blocks.any { it is MarkdownHeading && it.level == 2 })
        assertTrue(blocks.any { it is MarkdownCode && it.language == "kotlin" })
        val completion = blocks.filterIsInstance<MarkdownParagraph>().single()
        assertTrue(completion.text.hasSpanCovering("Done") { it.fontWeight == FontWeight.Bold })
    }

    @Test
    fun unfinishedOrOutdatedSnapshotCannotBeUsedForStableHandoff() {
        val parser = StreamingGfmParserSession()
        val content = "reply content"

        assertNull(parser.parse(content, isComplete = false).completedDocumentFor(content))
        val completed = parser.parse(content, isComplete = true)
        assertNull(completed.completedDocumentFor("reply content and new addition"))
        assertNull(completed.completedDocumentFor("corrected content"))
    }

    @Test
    fun completedSnapshotIncludesReferenceLinksBeforeStableHandoff() {
        val content = "see [docs][atom]\n\n[atom]: https://example.com/docs"
        val snapshot = StreamingGfmParserSession().parse(content, isComplete = true)

        // link definitions produce no block; in the completed snapshot the reference is resolved into a clickable Url annotation.
        val paragraph = snapshot.document.blocks.single() as MarkdownParagraph
        assertEquals("see docs", paragraph.text.text)
        assertEquals(listOf("https://example.com/docs"), paragraph.text.linkUrls())
    }

    @Test
    fun correctedSnapshotDoesNotRetainOrMutatePreviousLinkDefinitions() {
        val parser = StreamingGfmParserSession()
        val original = parser.parse("[docs][atom]\n\n[atom]: https://example.com/docs", isComplete = true)
        val replacement = parser.parse("[docs][atom]", isComplete = true)

        // references without a matching definition keep their original text and carry no link annotation.
        val replacementParagraph = replacement.document.blocks.single() as MarkdownParagraph
        assertEquals("[docs][atom]", replacementParagraph.text.text)
        assertTrue(replacementParagraph.text.linkUrls().isEmpty())

        val originalParagraph = original.document.blocks.single() as MarkdownParagraph
        assertEquals(listOf("https://example.com/docs"), originalParagraph.text.linkUrls())
    }

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

    private fun AnnotatedString.linkUrls(): List<String> =
        getLinkAnnotations(0, length).mapNotNull { range -> (range.item as? LinkAnnotation.Url)?.url }
}
