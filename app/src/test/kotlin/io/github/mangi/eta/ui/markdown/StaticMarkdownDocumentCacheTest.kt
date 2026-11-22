package io.github.mangi.eta.ui.markdown

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StaticMarkdownDocumentCacheTest {
    private val style = MarkdownInlineStyle.Default

    @Test
    fun smallDocumentsRespectTheEntryBudgetAndLeastRecentlyUsedOrder() {
        val cache = cache()
        val first = cache.parse("first item", style)
        cache.parse("second item", style)
        cache.parse("third item", style)
        cache.parse("fourth item", style)
        assertSame(first, cache.get("first item", style))

        cache.parse("fifth item", style)

        assertSame(first, cache.get("first item", style))
        assertNull(cache.get("second item", style))
        assertNotNull(cache.get("third item", style))
        assertNotNull(cache.get("fourth item", style))
        assertNotNull(cache.get("fifth item", style))
    }

    @Test
    fun totalSourceCharactersEvictBeforeTheEntryLimitIsReached() {
        val cache = cache()
        val first = "A".repeat(40)
        val second = "B".repeat(40)
        val third = "C".repeat(40)
        cache.parse(first, style)
        val retained = cache.parse(second, style)

        val latest = cache.parse(third, style)

        assertNull(cache.get(first, style))
        assertSame(retained, cache.get(second, style))
        assertSame(latest, cache.get(third, style))
    }

    @Test
    fun oversizedStaticDocumentIsParsedWithoutDisplacingSmallerDocuments() {
        val cache = cache()
        val small = cache.parse("retained short reply", style)
        val oversized = "x".repeat(61)

        val rendered = cache.parse(oversized, style)

        assertEquals(oversized, (rendered.blocks.single() as MarkdownParagraph).text.text)
        assertNull(cache.get(oversized, style))
        assertSame(small, cache.get("retained short reply", style))
    }

    @Test
    fun streamingHandoffUsesTheSameSingleDocumentAdmissionLimit() {
        val cache = cache()
        val small = cache.parse("small document before streaming handoff", style)
        val oversized = StreamingGfmParserSession().parse("x".repeat(61), isComplete = true)

        cache.put(oversized)

        assertNull(cache.get(oversized.originalSource, style))
        assertSame(small, cache.get("small document before streaming handoff", style))

        val accepted = StreamingGfmParserSession().parse("x".repeat(60), isComplete = true)
        cache.put(accepted)
        assertSame(accepted.document, cache.get(accepted.originalSource, style))
    }

    private fun cache() = StaticMarkdownDocumentCache(
        sourceCharBudget = 100,
        maxEntries = 4,
        maxDocumentSourceChars = 60,
    )
}
