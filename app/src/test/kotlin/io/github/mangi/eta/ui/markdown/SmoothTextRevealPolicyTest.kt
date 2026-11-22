package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothTextRevealPolicyTest {
    @Test
    fun coordinatorTracksBackgroundAnimationSuspension() {
        val coordinator = SmoothTextRevealCoordinator()

        assertEquals(false, coordinator.isAnimationPaused)
        coordinator.pauseAnimationsAndCatchUp()
        assertEquals(true, coordinator.isAnimationPaused)
        coordinator.resumeAnimationsAfterCatchUp()
        assertEquals(false, coordinator.isAnimationPaused)
    }

    @Test
    fun emptyAndOrdinaryTextExposeEveryGraphemeBoundary() {
        assertArrayEquals(intArrayOf(0), graphemeBoundaries(""))
        assertArrayEquals(intArrayOf(0, 1, 2, 3), graphemeBoundaries("AxB"))
    }

    @Test
    fun emojiSurrogatePairIsOneGrapheme() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("A😀B"),
        )
    }

    @Test
    fun extendedEmojiSequencesAreNeverSplit() {
        assertArrayEquals(
            intArrayOf(0, 1, 12, 13),
            graphemeBoundaries("A👨‍👩‍👧‍👦B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 5, 6),
            graphemeBoundaries("A👍🏽B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 5, 6),
            graphemeBoundaries("A🇨🇳B"),
        )
    }

    @Test
    fun combiningMarkAndCrLfStayInOneGrapheme() {
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("Ae\u0301B"),
        )
        assertArrayEquals(
            intArrayOf(0, 1, 3, 4),
            graphemeBoundaries("A\r\nB"),
        )
    }

    @Test
    fun appendedTextOnlyRebuildsTheLastPotentiallyExtendedGrapheme() {
        val familyPrefix = "A👨‍👩"
        val prefixBoundaries = graphemeBoundaries(familyPrefix)
        val family = "$familyPrefix‍👧‍👦B"

        assertArrayEquals(
            graphemeBoundaries(family),
            updateGraphemeBoundaries(familyPrefix, prefixBoundaries, family),
        )
        assertArrayEquals(
            graphemeBoundaries("A\r\nB"),
            updateGraphemeBoundaries("A\r", graphemeBoundaries("A\r"), "A\r\nB"),
        )
    }

    @Test
    fun nonAppendReplacementFallsBackToACompleteBoundaryScan() {
        val previous = "alpha 😀"
        val replacement = "beta 👨‍👩‍👧‍👦"

        assertArrayEquals(
            graphemeBoundaries(replacement),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    @Test
    fun replacementSharingPrefixInsideSurrogatePairFallsBackToCompleteScan() {
        // incremental rebuild only handles pure appends; a replacement is fully rescanned even if it shares a UTF-16 prefix, so surrogate pairs are never split.
        assertReplacementFallsBackToCompleteScan(previous = "😀 alpha", replacement = "😁 beta")
        assertReplacementFallsBackToCompleteScan(previous = "A😀x", replacement = "A😀y")
        assertReplacementFallsBackToCompleteScan(previous = "first", replacement = "second")
    }

    @Test
    fun changedExtendedGraphemeFallsBackToCompleteScanWithoutSplitting() {
        // the prefix shared by "👨‍👩X" and "👨‍👧Y" ends in the middle of a ZWJ sequence, so it must be fully rescanned rather than reusing a half grapheme.
        val previous = "👨‍👩X"
        val replacement = "👨‍👧Y"

        assertArrayEquals(
            intArrayOf(0, 5, 6),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    @Test
    fun revealSpeedUsesBaseRateThenCatchesUpWithoutCeiling() {
        assertEquals(36f, smoothRevealSpeed(totalBacklog = 0f), FLOAT_TOLERANCE)
        assertEquals(45f, smoothRevealSpeed(totalBacklog = 9f), FLOAT_TOLERANCE)
        assertEquals(100f, smoothRevealSpeed(totalBacklog = 20f), FLOAT_TOLERANCE)
        assertEquals(5_000f, smoothRevealSpeed(totalBacklog = 1_000f), FLOAT_TOLERANCE)
        assertEquals(50_000f, smoothRevealSpeed(totalBacklog = 10_000f), FLOAT_TOLERANCE)
    }

    @Test
    fun normalFrameAdvancesFractionallyAtBaseRate() {
        assertEquals(
            0.6f,
            advanceSmoothReveal(
                current = 0f,
                target = 10f,
                elapsedSeconds = 1f / 60f,
                totalBacklog = 1f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun catchUpAdvancesBeyondFormerSpeedCap() {
        assertEquals(
            253f,
            advanceSmoothReveal(
                current = 3f,
                target = 1_000f,
                elapsedSeconds = 0.05f,
                totalBacklog = 1_000f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun sustainedFastOutputDoesNotAccumulateUnboundedBacklog() {
        var target = 0f
        var progress = 0f
        repeat(600) {
            target += 20f
            progress = advanceSmoothReveal(progress, target, 1f / 60f, target - progress)
        }
        assertTrue(target - progress < 300f)
        repeat(90) {
            progress = advanceSmoothReveal(progress, target, 1f / 60f, target - progress)
        }
        assertEquals(target, progress, FLOAT_TOLERANCE)
    }

    @Test
    fun frameAdvanceClampsInvalidTimeAndTargetBounds() {
        assertEquals(
            2f,
            advanceSmoothReveal(
                current = 2f,
                target = 10f,
                elapsedSeconds = -1f,
                totalBacklog = 10f,
            ),
            FLOAT_TOLERANCE,
        )
        assertEquals(
            5f,
            advanceSmoothReveal(
                current = 4.75f,
                target = 5f,
                elapsedSeconds = 1f,
                totalBacklog = 100f,
            ),
            FLOAT_TOLERANCE,
        )
        assertEquals(
            5f,
            advanceSmoothReveal(
                current = 7f,
                target = 5f,
                elapsedSeconds = 1f,
                totalBacklog = 100f,
            ),
            FLOAT_TOLERANCE,
        )
    }

    @Test
    fun markdownDocumentCollapsesSourceBlankLinesIntoSemanticBlocks() {
        val snapshot = StreamingGfmParserSession().parse(
            source = "first paragraph\n\n\nsecond paragraph",
            isComplete = true,
        )

        val blocks = snapshot.document.blocks
        assertEquals(2, blocks.size)
        assertEquals(listOf("first paragraph", "second paragraph"), blocks.map { block -> (block as MarkdownParagraph).text.text })
    }

    @Test
    fun markdownBlockGapBuildsReadableHierarchyWithoutLeadingGap() {
        val first = MarkdownParagraph(0, AnnotatedString("first paragraph"))
        val second = MarkdownParagraph(4, AnnotatedString("second paragraph"))
        val heading = MarkdownHeading(8, level = 2, text = AnnotatedString("subsection"))
        val subHeading = MarkdownHeading(12, level = 3, text = AnnotatedString("sub-subsection"))
        val list = MarkdownList(16, ordered = false, loose = false, items = emptyList())
        val table = MarkdownTable(20, alignments = emptyList(), header = emptyList(), rows = emptyList())
        val rule = MarkdownRule(24)
        val quote = MarkdownQuote(28, blocks = emptyList())
        val math = MarkdownMath(32, latex = "E=mc^2", rawSource = "\$\$E=mc^2\$\$", displayList = null)
        val secondMath = MarkdownMath(48, latex = "a=b", rawSource = "\$\$a=b\$\$", displayList = null)

        assertEquals(0.sp, markdownBlockGap(null, first, MarkdownTone.Answer))
        assertEquals(0.sp, markdownBlockGap(null, math, MarkdownTone.Answer))
        assertEquals(11.sp, markdownBlockGap(first, second, MarkdownTone.Answer))
        assertEquals(14.sp, markdownBlockGap(first, math, MarkdownTone.Answer))
        assertEquals(14.sp, markdownBlockGap(math, second, MarkdownTone.Answer))
        assertEquals(12.sp, markdownBlockGap(math, secondMath, MarkdownTone.Answer))
        assertEquals(10.sp, markdownBlockGap(heading, math, MarkdownTone.Answer))
        assertEquals(20.sp, markdownBlockGap(first, heading, MarkdownTone.Answer))
        assertEquals(16.sp, markdownBlockGap(first, subHeading, MarkdownTone.Answer))
        assertEquals(6.sp, markdownBlockGap(heading, second, MarkdownTone.Answer))
        assertEquals(8.sp, markdownBlockGap(heading, subHeading, MarkdownTone.Answer))
        assertEquals(8.sp, markdownBlockGap(first, list, MarkdownTone.Answer))
        assertEquals(13.sp, markdownBlockGap(table, first, MarkdownTone.Answer))
        assertEquals(16.sp, markdownBlockGap(first, rule, MarkdownTone.Answer))
        assertEquals(16.sp, markdownBlockGap(rule, heading, MarkdownTone.Answer))
        assertEquals(5.sp, markdownCompactGap(first, list, MarkdownTone.Answer))
        assertEquals(8.sp, markdownCompactGap(first, quote, MarkdownTone.Answer))
        assertEquals(8.sp, markdownCompactGap(first, math, MarkdownTone.Answer))

        // the spacing of thinking tone collapses overall.
        assertEquals(0.sp, markdownBlockGap(null, first, MarkdownTone.Thinking))
        assertEquals(8.25.sp, markdownBlockGap(first, second, MarkdownTone.Thinking))
        assertEquals(10.5.sp, markdownBlockGap(first, math, MarkdownTone.Thinking))
        assertEquals(15.sp, markdownBlockGap(first, heading, MarkdownTone.Thinking))
    }

    @Test
    fun listMarkerWaitsForItsOwnContentToStartRevealing() {
        val markerKey = RevealBlockKey(10)

        assertFalse(listMarkerVisible(revealActive = true, markerKey = markerKey, started = emptySet()))
        assertTrue(listMarkerVisible(revealActive = true, markerKey = markerKey, started = setOf(markerKey)))
    }

    @Test
    fun listMarkerStaysHiddenForEmptyItemsUntilRevealEnds() {
        // images are downgraded to inline links, so the special case of "an item with no text that stays visible because of an image" no longer exists;
        // empty items stay hidden during streaming, and non-streaming rendering no longer hides any marker.
        assertFalse(listMarkerVisible(revealActive = true, markerKey = null, started = emptySet()))
        assertTrue(listMarkerVisible(revealActive = false, markerKey = null, started = null))
        assertTrue(listMarkerVisible(revealActive = false, markerKey = RevealBlockKey(10), started = emptySet()))
    }

    private fun assertReplacementFallsBackToCompleteScan(previous: String, replacement: String) {
        assertArrayEquals(
            graphemeBoundaries(replacement),
            updateGraphemeBoundaries(previous, graphemeBoundaries(previous), replacement),
        )
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
