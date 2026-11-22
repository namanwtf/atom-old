package io.github.namanwtf.atom.ui.markdown

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.ratex.DisplayItem
import io.ratex.DisplayList
import io.ratex.RaTeXColor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MarkdownMathTest {
    private val textMeasurer by lazy {
        val context = RuntimeEnvironment.getApplication()
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(context),
            defaultDensity = Density(3f, fontScale = 1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
            defaultLocaleList = LocaleList(context.resources.configuration.locales.toLanguageTags()),
        )
    }

    private fun testCache(counter: AtomicInteger? = null) = MarkdownMathLayoutCache(
        engine = { latex, displayMode ->
            counter?.incrementAndGet()
            if ("\\bad" in latex) {
                null
            } else {
                DisplayList(
                    width = (latex.length * 0.55).coerceAtLeast(1.0),
                    height = if (displayMode) 1.25 else 0.82,
                    depth = if (displayMode) 0.55 else 0.34,
                    items = listOf(
                        DisplayItem.GlyphPath(
                            x = 0.0,
                            y = 0.0,
                            scale = 1.0,
                            font = "Math-Italic",
                            charCode = 'x'.code,
                            color = RaTeXColor(0f, 0f, 0f, 0f),
                        ),
                        DisplayItem.Line(
                            x = 0.0,
                            y = -0.25,
                            width = 1.0,
                            thickness = 0.04,
                            color = RaTeXColor(1f, 0.2f, 0.2f, 1f),
                        ),
                    ),
                )
            }
        }
    )

    @Test
    fun cjkAdjacentInlineMathRecognizesDelimitersAndAssignsBreathingKerning() {
        val session = StreamingGfmParserSession(testCache())
        val source = "Let \$x > 0\$, and (\$y = 1\$), which holds when \\(z_i = 2\\)."
        val paragraph = session.parse(source, isComplete = true)
            .document.blocks.single() as MarkdownParagraph

        assertEquals("Let \$x > 0\$, and (\$y = 1\$), which holds when \\(z_i = 2\\).", paragraph.text.text)
        assertEquals(3, paragraph.inlineMath.size)

        val first = paragraph.inlineMath[0]
        assertEquals("x > 0", first.latex)
        assertEquals("\$x > 0\$", first.rawSource)
        assertTrue(first.leadingSpaceEm > 0f)
        // the right side is adjacent to a full-width comma; the punctuation carries its own half-width spacing, so no extra whitespace is added.
        assertEquals(0f, first.trailingSpaceEm, 0.0001f)

        val second = paragraph.inlineMath[1]
        assertEquals("y = 1", second.latex)
        assertEquals(0f, second.leadingSpaceEm, 0.0001f)
        assertEquals(0f, second.trailingSpaceEm, 0.0001f)

        val third = paragraph.inlineMath[2]
        assertEquals("z_i = 2", third.latex)
        assertEquals("\\(z_i = 2\\)", third.rawSource)
        assertTrue(third.leadingSpaceEm > 0f)
        assertTrue(third.trailingSpaceEm > 0f)
    }

    @Test
    fun currencyRangesAndIsolatedDollarDoNotSwallowSubsequentFormula() {
        val session = StreamingGfmParserSession(testCache())
        listOf(
            "price \$4 and \$20 per million token",
            "cost range \$4-\$20 per month",
            "earned\$100, spent\$200",
            "escaped \\\$x + y\\\$ is not a formula",
        ).forEach { plain ->
            val paragraph = session.parse(plain, isComplete = true)
                .document.blocks.single() as MarkdownParagraph
            assertTrue("Expected no inline math in: $plain", paragraph.inlineMath.isEmpty())
        }

        val mixed = session.parse("unit price \$4, discount when \$n \\ge 10\$", isComplete = true)
            .document.blocks.single() as MarkdownParagraph
        assertEquals(1, mixed.inlineMath.size)
        assertEquals("n \\ge 10", mixed.inlineMath.single().latex)
        assertEquals("unit price \$4, discount when \$n \\ge 10\$", mixed.text.text)
    }

    @Test
    fun displayMathInterruptsParagraphAndProtectsInternalMarkdownSyntax() {
        val session = StreamingGfmParserSession(testCache())
        val source = """
            derivation follows:
            $$
            \begin{aligned}
            x &= a + b \\
            - c

            = d
            \end{aligned}
            $$
            QED.
        """.trimIndent()

        val blocks = session.parse(source, isComplete = true).document.blocks
        assertEquals(3, blocks.size)
        assertEquals("derivation follows:", (blocks[0] as MarkdownParagraph).text.text)
        val math = blocks[1] as MarkdownMath
        assertTrue(math.latex.contains("- c"))
        assertTrue(math.latex.contains("= d"))
        assertNotNull(math.displayList)
        assertEquals("QED.", (blocks[2] as MarkdownParagraph).text.text)
    }

    @Test
    fun blockQuoteAndListDisplayMathStripOuterContainerMarkers() {
        val session = StreamingGfmParserSession(testCache())
        val quoted = session.parse("> Theorem:\n> $$\n> a + b = c\n> $$", isComplete = true)
            .document.blocks.single() as MarkdownQuote
        val quotedMath = quoted.blocks.last() as MarkdownMath
        assertEquals("a + b = c", quotedMath.latex)
        assertFalse(quotedMath.rawSource.contains(">"))

        val list = session.parse("1. First step:\n   \\[\n   x^2 + 1 = 0\n   \\]", isComplete = true)
            .document.blocks.single() as MarkdownList
        val listMath = list.items.single().blocks.last() as MarkdownMath
        assertEquals("x^2 + 1 = 0", listMath.latex)
        assertTrue(listMath.rawSource.startsWith("\\["))
        assertTrue(listMath.rawSource.endsWith("\\]"))
    }

    @Test
    fun streamingWaitsForClosingDelimiterAndReusesCachedLayouts() {
        val engineCalls = AtomicInteger(0)
        val cache = testCache(engineCalls)
        val session = StreamingGfmParserSession(cache)

        // an inline half-open interval $[0, 1)$ must not be truncated by streaming unclosed-link detection.
        val halfOpen = session.parse("continue output on the interval \$[0, 1)\$", isComplete = false)
        val halfOpenParagraph = halfOpen.document.blocks.single() as MarkdownParagraph
        assertEquals("continue output on the interval \$[0, 1)\$", halfOpenParagraph.text.text)
        assertEquals("[0, 1)", halfOpenParagraph.inlineMath.single().latex)

        // an unclosed multiline block containing a pipe `|A|` must not be hidden as an unconfirmed table.
        val unclosedDeterminant = session.parse("\$\$\n|A| = ad - bc", isComplete = false)
        assertTrue(unclosedDeterminant.renderedSource.contains("|A| = ad - bc"))
        assertTrue(unclosedDeterminant.document.blocks.none { it is MarkdownMath })

        // re-layout after closing, and reuse the same MarkdownMath instance and engine cache when a later paragraph is appended.
        engineCalls.set(0)
        val closedBlock = session.parse("\$\$\n|A| = ad - bc\n\$\$", isComplete = false)
        val firstMathInstance = closedBlock.document.blocks.single() as MarkdownMath
        assertEquals(1, engineCalls.get())

        val withNextParagraph = session.parse(
            "\$\$\n|A| = ad - bc\n\$\$\n\nwhere \$a\$ is the leading term, continuing with more explanation",
            isComplete = false,
        )
        assertSame(firstMathInstance, withNextParagraph.document.blocks.first())
        assertEquals(2, engineCalls.get())

        // when appending more text to the same last paragraph, the already-closed `$a$` at the paragraph start hits the formula layout cache directly without re-invoking the engine.
        session.parse(
            "\$\$\n|A| = ad - bc\n\$\$\n\nwhere \$a\$ is the leading term, continuing with more explanation and more text",
            isComplete = false,
        )
        assertEquals(2, engineCalls.get())
    }

    @Test
    fun bindDefaultColorReplacesSentinelAndPreservesExplicitFormulaColors() {
        val targetColor = Color(red = 0.9f, green = 0.85f, blue = 0.8f, alpha = 0.6f)
        val dl = testCache().layout("x", displayMode = false)!!
        val bound = dl.bindDefaultColor(targetColor)
        val glyph = bound.items[0] as DisplayItem.GlyphPath
        val explicitLine = bound.items[1] as DisplayItem.Line

        assertEquals(targetColor.red, glyph.color.r, 0.0001f)
        assertEquals(targetColor.green, glyph.color.g, 0.0001f)
        assertEquals(targetColor.blue, glyph.color.b, 0.0001f)
        assertEquals(targetColor.alpha, glyph.color.a, 0.0001f)
        assertEquals(1f, explicitLine.color.r, 0.0001f)
        assertEquals(0.2f, explicitLine.color.g, 0.0001f)
        assertEquals(targetColor.alpha, explicitLine.color.a, 0.0001f)
    }

    @Test
    fun boldAndTableCellsPreserveInlineMathAndSpanColors() {
        val style = MarkdownInlineStyle(
            linkColor = Color(0xFF3482FF),
            codeBackground = Color(0x1F808080),
            strongColor = Color(0xFF112233),
        )
        val session = StreamingGfmParserSession(testCache())
        val doc = session.parse(
            "**Key conclusion：\$E = mc^2\$.**following text\n\n| Metric | Formula |\n| --- | --- |\n| Complexity | \$O(n \\log n)\$ |",
            isComplete = true,
            style = style,
        ).document

        val paragraph = doc.blocks[0] as MarkdownParagraph
        val spec = paragraph.inlineMath.single()
        assertEquals("E = mc^2", spec.latex)
        assertTrue(paragraph.text.spanStyles.any {
            it.start <= spec.rangeStart && it.end >= spec.rangeEnd && it.item.color == style.strongColor
        })

        val table = doc.blocks[1] as MarkdownTable
        val formulaCell = table.rows.single()[1]
        assertEquals("\$O(n \\log n)\$", formulaCell.text.text)
        assertEquals("O(n \\log n)", formulaCell.inlineMath.single().latex)
    }

    @Test
    fun inlineMathBoxMetricsKeepExactBaselineAlignmentAndAdaptToNarrowWidths() {
        // verify zero baseline error and no-clipping guarantees across different glyph heights and vertically asymmetric formulas.
        listOf(
            Triple(0.85, 0.35, -42 to 11),
            Triple(1.40, 0.05, -44 to 12),
            Triple(0.45, 0.95, -36 to 10),
        ).forEach { (heightEm, depthEm, fontMetrics) ->
            val metrics = computeInlineMathBoxMetrics(
                displayListWidthEm = 2.4,
                displayListHeightEm = heightEm,
                displayListDepthEm = depthEm,
                hasItems = true,
                textFontSizePx = 48f,
                fontAscentPx = fontMetrics.first,
                fontDescentPx = fontMetrics.second,
                leadingSpaceEm = 0.11f,
                trailingSpaceEm = 0.11f,
                maxAvailableWidthPx = 900,
            )
            val heightAbovePx = (heightEm * metrics.mathFontSizePx).toFloat() + 1f
            val depthBelowPx = (depthEm * metrics.mathFontSizePx).toFloat() + 1f
            val topFromBaseline = (fontMetrics.first + fontMetrics.second - metrics.boxHeightPx) / 2
            val renderedBaselineFromLineBaseline = topFromBaseline + metrics.drawTopPx + heightAbovePx

            assertEquals(0f, renderedBaselineFromLineBaseline, 0.01f)
            assertTrue(metrics.drawTopPx >= 0f)
            assertTrue(metrics.drawTopPx + heightAbovePx + depthBelowPx <= metrics.boxHeightPx)
            assertFalse(metrics.scrollable)
        }

        // slightly over-wide on a narrow screen: gently shrink to fit a single line without enabling internal scrolling.
        val gentlyScaled = computeInlineMathBoxMetrics(
            displayListWidthEm = 5.0,
            displayListHeightEm = 0.8,
            displayListDepthEm = 0.2,
            hasItems = true,
            textFontSizePx = 48f,
            fontAscentPx = -42,
            fontDescentPx = 11,
            leadingSpaceEm = 0f,
            trailingSpaceEm = 0f,
            maxAvailableWidthPx = 220,
        )
        assertFalse(gentlyScaled.scrollable)
        assertTrue(gentlyScaled.boxWidthPx <= 220)

        // over-long inline formula on a narrow screen: lock the maximum placeholder width and enable internal horizontal scrolling.
        val scrollableLong = computeInlineMathBoxMetrics(
            displayListWidthEm = 20.0,
            displayListHeightEm = 0.8,
            displayListDepthEm = 0.2,
            hasItems = true,
            textFontSizePx = 48f,
            fontAscentPx = -42,
            fontDescentPx = 11,
            leadingSpaceEm = 0f,
            trailingSpaceEm = 0f,
            maxAvailableWidthPx = 300,
        )
        assertTrue(scrollableLong.scrollable)
        assertEquals(300, scrollableLong.boxWidthPx)
        assertTrue(scrollableLong.contentWidthPx > scrollableLong.boxWidthPx)
    }

    @Test
    fun placeholderMeasurementPreservesLatexSourceAndDoesNotTruncateAcrossLineWraps() {
        val session = StreamingGfmParserSession(testCache())
        val paragraph = session.parse(
            "This is a fairly long intro paragraph used to trigger automatic wrapping followed immediately by an inline Formula \$a_1 + b_1 = c_1\$ and then following text.",
            isComplete = true,
        ).document.blocks.single() as MarkdownParagraph

        val spec = paragraph.inlineMath.single()
        val placeholders = listOf(
            androidx.compose.ui.text.AnnotatedString.Range(
                item = Placeholder(
                    width = 64.sp,
                    height = 24.sp,
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
                start = spec.rangeStart,
                end = spec.rangeEnd,
            )
        )
        val layout = textMeasurer.measure(
            text = paragraph.text,
            style = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            placeholders = placeholders,
            constraints = Constraints(maxWidth = 480),
        )

        assertNotNull("Wrapped inline math placeholder must not be truncated to null", layout.placeholderRects.single())
        assertEquals(
            "\$a_1 + b_1 = c_1\$",
            layout.layoutInput.text.text.substring(spec.rangeStart, spec.rangeEnd),
        )
        val revealPath = layout.getPathForRange(spec.rangeStart, spec.rangeEnd)
        assertFalse(revealPath.isEmpty)
    }

    @Test
    fun atomicRevealBoundariesKeepFormulaAsSingleStepAndMapProgressMonotonically() {
        val text = "Let \$a_1 + b_1\$ hold"
        val formulaStart = text.indexOf('$')
        val formulaEnd = text.lastIndexOf('$') + 1
        val spans = listOf(RevealAtomicSpan(formulaStart, formulaEnd))

        assertArrayEquals(
            intArrayOf(0, 1, 2, formulaEnd, formulaEnd + 1, formulaEnd + 2, formulaEnd + 3),
            graphemeBoundariesWithAtomicSpans(text, spans),
        )

        val boundaries = graphemeBoundariesWithAtomicSpans(text, spans)
        // when reveal has not yet reached the formula, keep the original precise grapheme progress.
        assertEquals(1.4f, mapProgressAcrossAtomicChange(boundaries, oldCharOffset = 1, oldFraction = 0.4f), 0.001f)
        // when reveal enters a formula before it closes, it aligns to the formula's end boundary (index 3) at the moment of closing, without skipping the trailing "holds".
        assertEquals(3f, mapProgressAcrossAtomicChange(boundaries, oldCharOffset = 6, oldFraction = 0.7f), 0.001f)
    }
}
